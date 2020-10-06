(ns db-fuzzer.generate
  "Contains hand-built generation functions.
  For generators that are automatically built via BNF, take a look at sql_spec.cljc ."
  (:require [honeysql.core :as sql]
            [honeysql.helpers :as helpers]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [db-fuzzer.sql-spec]))

(defn gen-simple-select
  "Creates a simple, contextually valid select statement."
  [introspection]
  (let [table (rand-nth (keys (:tables introspection)))
        column (rand-nth (keys (get (:tables introspection) table)))
        column-metadata (get-in introspection [table column])
        ]
    {:select [:*]
     :from   [table]
     :where  [:= column
              (if (#{:varchar} (:data-type column-metadata))
                (gen/generate (s/gen string?))
                (gen/generate (s/gen int?)))]}))

(defn gen-value [data-type]
  (if (#{:varchar} data-type)
                (gen/generate (s/gen string?))
                (gen/generate (s/gen int?))))

(defn dotted-keyword
  "next.jdbc uses keywords to refer to tables and columns.
  To generate table.column in sql, it uses a dotted key
  like :widget.id to represent widget.id in the SQL.
  E.g. (dotted-keyword :widget :id), which evals to :widget/id ."
  [kw1 kw2]
  (keyword (str (name kw1) "." (name kw2))))

(defn next-alias [alias-atom]
  (keyword (str "a" (swap! alias-atom inc))))

(defn gen-join [introspection target-table alias-atom]
  (let [join-table (rand-nth (keys (:tables introspection)))
        join-table-alias (next-alias alias-atom)
        join-column (rand-nth (keys (get-in introspection [:tables join-table])))
        target-column (rand-nth (keys (get-in introspection [:tables target-table])))]
    [[join-table join-table-alias]
     [:=
      (dotted-keyword join-table-alias join-column)
      (dotted-keyword target-table target-column)]]))

(defn rand-set-element
  "Picks a number of elements at random from a set.
  E.g. (generate/rand-set-element 5 #{:a :b :c}) -> (:c :c :a :b :a))"
  ([set-coll]
   (rand-set-element 1 set-coll))
  ([n set-coll]
   (repeatedly n (fn [] (rand-nth (vec set-coll))))))

(defn gen-complete
  "Generates a fancy select statement.
  Can fail to generate when the introspection data is empty."
  ([introspection]
   (gen-complete introspection {}))
  ([introspection {:keys [complexity limit] :as opts}]
   (if (empty? introspection)
     false
     (let [table (rand-nth (keys (get introspection :tables)))
           column (rand-nth (keys (get-in introspection [:tables table])))
           column-metadata (get-in introspection [table column])
           complexity (or complexity 2)
           complexity-tokens (rand-set-element complexity #{:join :left-join :right-join :where})
           alias-atom (atom 0)
           ]
       ;; (println "gen-complete: " (first introspection) table alias-atom)
       ;; (println complexity complexity-tokens (count (filter #(= :join %) complexity-tokens)))
       (merge
        {:select [:*]                                       ;;can i replace
         :from   [table]
         :join (vec (apply concat (repeatedly
                                   (count (filter #(= :join %) complexity-tokens)) ;; run gen-join for each time :join was picked as a complexity token
                                   #(gen-join introspection table alias-atom))))
         :left-join (vec (apply concat (repeatedly
                                        (count (filter #(= :left-join %) complexity-tokens))
                                        #(gen-join introspection table alias-atom))))
         :right-join (vec (apply concat (repeatedly
                                         (count (filter #(= :right-join %) complexity-tokens))
                                         #(gen-join introspection table alias-atom))))}
        (when limit
          {:limit limit})
        ;; Add in the where clause if called for
        (when (contains? (set complexity-tokens) :where)
          {:where (vec (concat [(rand-nth [:or :and])] (repeatedly
                                                        (count (filter #(= :where %) complexity-tokens))
                                                        (fn []
                                                          [:= (dotted-keyword table column) (gen-value (:data-type column-metadata))]))))}))))))

(defn show-tables
  "An example of a raw SQL query that's platform-specific"
  [introspection]
  ["show tables;"])

(defn gen-table-name []
  (as-> (gen/generate (s/gen string?)) $
    (cond
      (re-matches #"^[0-9]*$" $) (str "a" $) ;; sql table names can't be solely numeric or empty
      :default $)))

(defn mysql-create-table
  ([introspection]
   (mysql-create-table introspection {}))
  ([introspection {:keys [complexity] :as opts}]
   (let [table-name (gen-table-name)]
     (println "Picking table-name: " table-name)
     [(str "create table if not exists " table-name  "(
               id int key
            )") ])))

(defn pass-invalid-queries [msg]
  (or
   (re-matches #".*Unknown column.*" msg)
   (re-matches #".*Unknown table." msg)
   (re-matches #".*Table.* doesn't exist" msg)
   (re-matches #".*You have an error in your SQL syntax.*" msg)))

;; This part is important. It's used by the test runner to know which generators can be run and how to run them.
(def generators
  {:complete-select
   {:gen-fn gen-complete
    :output-type :honey-sql
    :read-only? true
    :supported-platforms :all}
   :raw-show-tables
   {:gen-fn show-tables
    :output-type :raw-sql
    :read-only? true
    :supported-platforms #{"mysql"}}
   :raw-create-table
   {:gen-fn mysql-create-table
    :output-type :raw-sql
    :read-only? false
    :supported-platforms #{"mysql"}}
   :ast-create-table
   {:gen-fn db-fuzzer.sql-spec/create-statement-shim
    :output-type :raw-sql
    :read-only? false
    :supported-platforms #{"mysql"}}
   :ast-select
   {:gen-fn db-fuzzer.sql-spec/select-statement-shim
    :output-type :raw-sql
    :read-only? true
    :supported-platforms #{"mysql"}}
   :ast-select-munged
   {:gen-fn db-fuzzer.sql-spec/select-statement-munged-shim
    :output-type :raw-sql
    :read-only? true
    :supported-platforms #{"mysql"}
    :error-message-pass-fn pass-invalid-queries}})
