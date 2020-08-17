(ns db-fuzzer.introspect
  "Introspects a database to pull back table and column information for later use in query generation."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honeysql.core :as sql]
            [honeysql.helpers :as helpers]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]))
;; -----------------------------------
;; The spec for the introspection data
;; -----------------------------------
(s/def ::nullable? boolean?)
(s/def ::character-string-type #{:character :char :character-varying :char-varying :varchar})
(s/def ::bit-string-type #{:bit})
(s/def ::national-character-string-type #{:national-character :national-char :nchar})
(s/def ::exact-numeric-type #{:numeric :decimal :dec :integer :int :tinyint :smallint :mediumint :bigint})
(s/def ::approximate-numeric-type #{:float :real :double})
(s/def ::numeric-type (s/or :exact-numeric-type ::exact-numeric-type
                             :approximate-numeric-type ::approximate-numeric-type))
(s/def ::datetime-type #{:date :time :timestamp})
(s/def ::data-type (s/or ::character-string-type ::character-string-type
                         ::bit-string-type ::bit-string-type
                         ::national-character-string-type ::national-character-string-type
                         ::numeric-type ::numeric-type
                         ::datetime-type ::datetime-type
                         :misc #{:json :text :tinytext :mediumtext :longtext :datetime :timestamp :char})) ;; TODO cross-reference MySQL documentation to ensure we got all the keys. Also might make since to break these into sets so of text, number, etc. to make it easier to generate inputs.
(s/def ::char-max-length (s/nilable number?))
(s/def ::numeric-precision (s/nilable number?))

(s/def ::column-metadata (s/keys :req-un [::data-type] :opt-un [::nullable? ::char-max-length ::numeric-precision]))
(s/def ::table (s/map-of keyword? ::column-metadata)) ;; e.g. (s/conform ::table {:widget_name {:data-type :varchar} :widget_id {:data-type :bigint}})
(s/def ::tables (s/map-of keyword? ::table))
(s/def ::version string?)
(s/def ::platform #{"mysql" "mariadb"})
(s/def ::introspection (s/keys :req-un [::tables ::version ::platform])) ;; e.g. (s/conform ::introspection {:widgets {:widget_name {:data-type :varchar} :widget_id {:data-type :bigint}} :gadgets {:id {:data-type :bigint}}})

;; NOTE: if you need to generate a "test schema" for a database, just run (gen/generate (s/gen :db-fuzzer.introspect/table)). At present, the keys generated won't necesarily be SQL-valid tokens -- the spec will need to be more detailed if we want to ensure that.

;; Here's a sample introspection object with just one table with five columns in it.
(def sample-introspection
  {:platform "mysql"
   :version "8.0.21-cluster"
   :tables
   {:wp_snippets
    {:id
     {:nullable? false,
      :data-type :mediumint,
      :char-max-length nil,
      :numeric-precision 7},
     :snippet
     {:nullable? false,
      :data-type :varchar,
      :char-max-length 255,
      :numeric-precision nil},
     :audio_attachment_id
     {:nullable? false,
      :data-type :bigint,
      :char-max-length nil,
      :numeric-precision 19},
     :user_id
     {:nullable? false,
      :data-type :bigint,
      :char-max-length nil,
      :numeric-precision 19},
     :user_display_name
     {:nullable? false,
      :data-type :varchar,
      :char-max-length 250,
      :numeric-precision nil}}}})

(defn columns-from-mysql-table
  "Reads column metadata from a MySQL table.
  Example output:
  {:id \"mediumint(9)\",
   :snippet \"varchar(255)\",
   :audio_attachment_id \"bigint(20)\",
   :user_id \"bigint(20)\",
   :user_display_name \"varchar(250)\"}"
  [db table-name]
  (let [table-name (if (keyword? table-name)
                     (name table-name)
                     table-name)]
    (as-> (jdbc/execute! db ["select COLUMN_NAME, IS_NULLABLE, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, NUMERIC_PRECISION from information_schema.columns where TABLE_NAME=?;" table-name]) $
      (map (fn [column-map]
             {(keyword (:COLUMNS/COLUMN_NAME column-map))
              {:nullable? (= "YES" (:COLUMNS/IS_NULLABLE column-map))
               :data-type (keyword (:COLUMNS/DATA_TYPE column-map))
               :char-max-length (:COLUMNS/CHARACTER_MAXIMUM_LENGTH column-map)
               :numeric-precision (:COLUMNS/NUMERIC_PRECISION column-map)}}) $) 
      (apply merge $))))

(defn introspect-mysql [db]
  (let [tables
        (as-> (jdbc/execute! db ["select table_name from information_schema.tables where table_schema = ?;" (:dbname db)]) $
          (mapv :TABLES/TABLE_NAME $)
          (map (fn [table-name]
                 {(keyword table-name) (columns-from-mysql-table db table-name)}) $)
          (apply merge $))
        
        version (get (first (jdbc/execute! db ["select @@version;"])) (keyword "@@version"))]
    {:tables tables
     :version version
     :platform "mysql"}))

(defn introspect-mariadb [db]
  (let [tables
        (as-> (jdbc/execute! db ["select table_name from information_schema.tables where table_schema = ?;" (:dbname db)]) $
          (mapv :TABLES/table_name $)
          (map (fn [table-name]
                 {(keyword table-name) (columns-from-mysql-table db table-name)}) $)
          (apply merge $))
        
        version (get (first (jdbc/execute! db ["select @@version;"])) (keyword "@@version"))]
    {:tables tables
     :version version
     :platform "mariadb"}))

(defn introspect [db]
  (let [introspection
        (case (:dbtype db)
          "mysql" (introspect-mysql db)
          "mariadb" (introspect-mariadb db)
          nil)]
    (if (s/valid? ::introspection introspection) ;; Note: if the spec above gets too hard to maintain or you just want to skip the check, comment out this if statement and just return introspection. The purpose of this check is to pinpoint introspection errors early rather than wading through query output and wondering if the generator functions are broken.
      introspection
      (throw (Exception. "Introspection failed the spec: " (s/explain ::introspection introspection))))))
