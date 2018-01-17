(ns metabase.automagic-dashboards.core
  "Automatically generate questions and dashboards based on predefined
   heuristics."
  (:require [clojure.math.combinatorics :as combo]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clojure.walk :as walk]
            [kixi.stats.core :as stats]
            [medley.core :as m]
            [metabase.api.common :as api]
            [metabase.automagic-dashboards
             [populate :as populate]
             [rules :as rules]]
            [metabase.models
             [card :as card]
             [field :refer [Field]]
             [permissions :as perms]
             [table :refer [Table]]]
            [metabase.util :as u]
            [toucan.db :as db]))

(defmulti
  ^{:doc "Get a reference for a given model to be injected into a template
          (either MBQL, native query, or string)."
    :arglists '([template-type model])
    :private true}
  ->reference (fn [template-type model]
                [template-type (type model)]))

(defmethod ->reference [:mbql (type Field)]
  [_ {:keys [fk_target_field_id id link aggregation base_type]}]
  (let [reference (cond
                    link               [:fk-> link id]
                    fk_target_field_id [:fk-> id fk_target_field_id]
                    :else              [:field-id id])]
    (if (isa? base_type :type/DateTime)
      [:datetime-field reference (or aggregation :day)]
      reference)))

(defmethod ->reference [:string (type Field)]
  [_ {:keys [display_name]}]
  display_name)

(defmethod ->reference [:string (type Table)]
  [_ {:keys [display_name]}]
  display_name)

(defmethod ->reference [:native (type Field)]
  [_ {:keys [name table_id]}]
  (format "%s.%s" (-> table_id Table :name) name))

(defmethod ->reference [:native (type Table)]
  [_ {:keys [name]}]
  name)

(defmethod ->reference :default
  [_ form]
  form)

(def ^:private field-filters
  {:fieldspec (fn [fieldspec]
                (if (and (string? fieldspec)
                         (rules/ga-dimension? fieldspec))
                  (comp #{fieldspec} :name)
                  (fn [{:keys [base_type special_type]}]
                    (some #(isa? % fieldspec) [special_type base_type]))))
   :named     (fn [name-pattern]
                (comp (->> name-pattern
                           str/lower-case
                           re-pattern
                           (partial re-find))
                      str/lower-case
                      :name))})

(defn- numeric-key?
  "Workaround for our leaky type system which conflates types with properties."
  [{:keys [base_type special_type name]}]
  (and (isa? base_type :type/Number)
       (or (#{:type/PK :type/FK} special_type)
           (-> name str/lower-case (= "id")))))

(defn- filter-fields
  "Find all fields belonging to table `table` for which all predicates in
   `preds` are true."
  [preds table]
  (filter (every-pred (complement numeric-key?)
                      (->> preds
                           (keep (fn [[k v]]
                                   (when-let [pred (field-filters k)]
                                     (some-> v pred))))
                           (apply every-pred)))
          (db/select Field :table_id (:id table))))

(defn- filter-tables
  [tablespec context]
  (filter #(-> % :entity_type (isa? tablespec)) (:tables context)))

(defn- fill-template
  [template-type context bindings template]
  (str/replace template #"\[\[(\w+)\]\]"
               (fn [[_ identifier]]
                 (->reference template-type (or (bindings identifier)
                                                (-> identifier
                                                    rules/->entity
                                                    (filter-tables context)
                                                    first)
                                                identifier)))))

(defn- field-candidates
  [context {:keys [field_type links_to named] :as constraints}]
  (if links_to
    (filter (comp (->> (filter-tables links_to context)
                       (keep :link)
                       set)
                  :id)
            (field-candidates context (dissoc constraints :links_to)))
    (let [[tablespec fieldspec] field_type]
      (if fieldspec
        (let [[table] (filter-tables tablespec context)]
          (mapcat (fn [table]
                    (some->> table
                             (filter-fields {:fieldspec fieldspec
                                             :named     named})
                             (map #(assoc % :link (:link table)))))
                  (filter-tables tablespec context)))
        (filter-fields {:fieldspec tablespec
                        :named     named}
                       (:root-table context))))))

(defn- make-binding
  [context [identifier definition]]
  {(name identifier) (->> definition
                          (field-candidates context)
                          (map #(merge % definition))
                          (assoc definition :matches))})

(defn- bind-dimensions
  [context dimensions]
  (->> dimensions
       (map (comp (partial make-binding context) first))
       (apply merge-with (fn [a b]
                           (case (map (comp empty? :matches) [a b])
                             [false true] a
                             [true false] b
                             (max-key :score a b))))))

(defn- index-of
  [pred coll]
  (first (keep-indexed (fn [idx x]
                         (when (pred x)
                           idx))
                       coll)))

(defn- build-order-by
  [dimensions metrics order-by]
  (let [dimensions (set dimensions)]
    (for [[identifier ordering] (map first order-by)]
      [(if (= ordering "ascending")
         :asc
         :desc)
       (if (dimensions identifier)
         [:dimension identifier]
         [:aggregate-field (index-of #{identifier} metrics)])])))

(defn- have-permissions?
  [query]
  (perms/set-has-full-permissions-for-set? @api/*current-user-permissions-set*
                                           (card/query-perms-set query :write)))

(defn- build-query
  ([context bindings filters metrics dimensions limit order_by]
   (walk/postwalk
    (fn [subform]
      (if (rules/dimension-form? subform)
        (->> subform second bindings (->reference :mbql))
        subform))
    {:type     :query
     :database (:database context)
     :query    (cond-> {:source_table (-> context :root-table :id)}
                 (not-empty filters)
                 (assoc :filter (cond->> (map :filter filters)
                                  (> (count filters) 1) (apply vector :and)))

                 (not-empty dimensions)
                 (assoc :breakout dimensions)

                 (not-empty metrics)
                 (assoc :aggregation (map :metric metrics))

                 limit
                 (assoc :limit limit)

                 (not-empty order_by)
                 (assoc :order_by order_by))}))
  ([context bindings query]
   {:type     :native
    :native   {:query (fill-template :native context bindings query)}
    :database (:database context)}))

(defn- has-matches?
  [dimensions definition]
  (->> definition
       rules/collect-dimensions
       (every? (comp not-empty :matches dimensions))))

(defn- resolve-overloading
  "Find the overloaded definition with the highest `score` for which all
   referenced dimensions have at least one matching field."
  [{:keys [dimensions]} definitions]
  (apply merge-with (fn [a b]
                      (case (map has-matches? [a b])
                        [true false] a
                        [false true] b
                        (max-key :score a b)))
         definitions))

(defn- instantate-visualization
  [bindings v]
  (let [identifier->name (comp :name bindings)]
    (-> v
        (u/update-in-when ["map" "map.latitude_column"] identifier->name)
        (u/update-in-when ["map" "map.longitude_column"] identifier->name))))

(defn- instantiate-metadata
  [context bindings x]
  (let [fill-template (partial fill-template :string context bindings)]
    (-> x
        (update :title fill-template)
        (u/update-when :visualization (partial instantate-visualization bindings))
        (u/update-when :description fill-template))))

(defn- card-candidates
  "Generate all potential cards given a card definition and bindings for
   dimensions, metrics, and filters."
  [context {:keys [metrics filters dimensions score limit order_by query] :as card}]
  (let [order_by        (build-order-by dimensions metrics order_by)
        metrics         (map (partial get (:metrics context)) metrics)
        filters         (map (partial get (:filters context)) filters)
        score           (if query
                          score
                          (->> dimensions
                               (map (partial get (:dimensions context)))
                               (concat filters metrics)
                               (transduce (keep :score) stats/mean)
                               (* (/ score rules/max-score))))
        dimensions      (map (partial vector :dimension) dimensions)
        used-dimensions (rules/collect-dimensions [dimensions metrics filters query])]
    (->> used-dimensions
         (map (some-fn (comp :matches (partial get (:dimensions context)))
                       (comp #(filter-tables % context) rules/->entity)))
         (apply combo/cartesian-product)
         (keep (fn [instantiations]
                 (let [bindings (zipmap used-dimensions instantiations)
                       query    (if query
                                  (build-query context bindings query)
                                  (build-query context bindings
                                               filters
                                               metrics
                                               dimensions
                                               limit
                                               order_by))]
                   (when (have-permissions? query)
                     (-> (instantiate-metadata context bindings card)
                         (assoc :score score
                                :query query)))))))))

(defn- best-matching-rule
  "Pick the most specific among applicable rules.
   Most specific is defined as entity type specification the longest ancestor
   chain."
  [rules table]
  (some->> rules
           (filter #(isa? (:entity_type table) (:table_type %)))
           not-empty
           (apply max-key (comp count ancestors :table_type))))

(defn- linked-tables
  "Return all tables accessable from a given table with the paths to get there.
   If there are multiple FKs pointing to the same table, multiple entries will
   be returned."
  [table]
  (map (fn [{:keys [id fk_target_field_id]}]
         (-> fk_target_field_id Field :table_id Table (assoc :link id)))
       (db/select [Field :id :fk_target_field_id]
         :table_id (:id table)
         :fk_target_field_id [:not= nil])))

(defn link-table?
  "Is table comprised only of foregin keys and maybe a primary key?"
  [table]
  (empty? (db/select Field
            ;; :not-in returns false if field is nil, hence the workaround.
            {:where [:and [:= :table_id (:id table)]
                          [:or [:not-in :special_type ["type/FK" "type/PK"]]
                               [:= :special_type nil]]]})))

(defn automagic-dashboard
  "Create a dashboard for table `root` using the best matching heuristic."
  [root]
  (let [rule      (best-matching-rule (rules/load-rules) root)
        context   (as-> {:root-table root
                       :rule       (:table_type rule)
                       :tables     (concat [root] (linked-tables root))
                       :database   (:db_id root)} <>
                    (assoc <> :dimensions (bind-dimensions <> (:dimensions rule)))
                    (assoc <> :metrics (resolve-overloading <> (:metrics rule)))
                    (assoc <> :filters (resolve-overloading <> (:filters rule))))
        dashboard (->> (select-keys rule [:title :description])
                       (instantiate-metadata context {}))]
    (log/info (format "Applying heuristic %s to table %s."
                      (:table_type rule)
                      (:name root)))
    (log/info (format "Dimensions bindings:\n%s"
                      (->> context
                           :dimensions
                           (m/map-vals #(update % :matches (partial map :name)))
                           u/pprint-to-str)))
    (log/info (format "Using definitions:\nMetrics:\n%s\nFilters:\n%s"
                      (-> context :metrics u/pprint-to-str)
                      (-> context :filters u/pprint-to-str)))
    (some->> rule
             :cards
             (keep (comp (fn [[identifier card]]
                           (some->> card
                                    (card-candidates context)
                                    not-empty
                                    (hash-map (name identifier))))
                         first))
             (apply merge-with (partial max-key (comp :score first)))
             vals
             (apply concat)
             (populate/create-dashboard! dashboard)
             :id)))