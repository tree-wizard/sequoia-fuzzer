(ns db-fuzzer.core
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honeysql.core :as sql]
            [honeysql.helpers :as helpers]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.data]
            [cli-matic.core :refer [run-cmd]]
            [db-fuzzer.introspect :as introspect]
            [db-fuzzer.generate :as generate]
            [try-let :refer [try-let]]
            [clojure.java.jdbc :as sqlite-jdbc] ;; for accessing sqlite
            )
  (:gen-class))

;; TODO take these next two statements out before shipping -- they're jus tfor plaing at my REPL.
(def db {:dbtype "mysql"
         :dbname "playground"
         :user   "test"
         :password "test"})

(def logging-db
  {:classname   "org.sqlite.JDBC"
   :subprotocol "sqlite"
   :subname     "query-log.db"
   })

(defn create-logging-db-if-needed []
  (try (sqlite-jdbc/db-do-commands
        logging-db
        "create table if not exists queries(
            id integer primary key,
            result boolean not null,
            query text not null,
            error text null,
            comment null);"
        )
       (catch Exception e
         (println (.getMessage e)))))

(defn log-query [result query error]
  (try (sqlite-jdbc/db-do-commands
        logging-db
        (str
         "insert into queries (result,query,error) values (\""
         result
         "\",\""
         (clojure.string/replace query #"\"" "") ;; get rid of quotes since those will confuse SQLite
         "\",\""
         error
         "\");"))
       (catch Exception e
         (println (.getMessage e)))))

;;(defonce mysql-metadata (introspect/introspect db))

(def sqlmap {:select [:*]
             :from   ["wp-snippets"]
             :where  [:= :f.a "baz"]})

(defn test-runner [{:keys [dbtype dbname user password number show-results log-to-sqlite] :as opts}
                   {:keys [gen-fn output-type error-message-pass-fn] :as generator}]
  (when log-to-sqlite (create-logging-db-if-needed))
  (let [db {:dbtype dbtype
            :dbname dbname
            :user user
            :password password}
        introspection (introspect/introspect db)
        number (or number 3) ;; for convenience on the REPL, default number if not passed
        failed-query-count (atom 0)]
    (loop [run-num 1] ;; Perf note: OpenJDK seems to have a leak in OpenJDK <14 with java.lang.invoke.LambdaForm: https://bugs.openjdk.java.net/browse/JDK-8229011
      (when (<= run-num number)
        (do
          (let [generated-query (gen-fn introspection)
                query-to-run (if (= :honey-sql output-type)
                               (sql/format generated-query)
                               generated-query)]
            (if generated-query
              (try-let [result (jdbc/execute! db query-to-run)]
                       (let [result-vec [true generated-query (when show-results result)]]
                         (println result-vec)
                         (when log-to-sqlite (log-query true (str generated-query) nil))
                         result-vec)
                       (catch Exception e
                         (if (and error-message-pass-fn
                                  (not (error-message-pass-fn (ex-message e))))
                           (let [result-vec [false generated-query (ex-message e)]]
                             (println result-vec)
                             (when log-to-sqlite (log-query false (str generated-query) (ex-message e)))
                             (swap! failed-query-count inc)
                             result-vec)
                           (let [result-vec [true generated-query (ex-message e)]]
                             (println result-vec)
                             (when log-to-sqlite (log-query true (str generated-query) nil))
                             result-vec))))
              (do
                (println [false "Couldn't generate query"])
                [false "Couldn't generate query"])))
          (recur (inc run-num)))))
    (if (> @failed-query-count 0)
      (println @failed-query-count "queries failed.")
      (println "All queries ran successfully. No issues found."))
    nil ;; cli-matic does things with the return value, so pass it nil to avoid errors
    ))

(def CONFIGURATION
  {:command "db-fuzzer"
   :description (str "Introspective grammar-based SQL fuzzing tool."
                     "\n             Available Generators:"
                     "\n             --------------------\n"
                     (apply str (interpose
                                 "\n"
                                 (map (fn [k] (str "             " k))
                                      (keys generate/generators)))))
   :version "0.1.0-SNAPSHOT"
   :opts [{:as "user"
           :default :present ;; :present means that it's a required param
           :option "user"
           :short "u"
           :type :string}
          {:as "password"
           :default :present
           :option "password"
           :short "p"
           :type :string}
          {:as "Database platform
   mysql
   mariadb"
           :default :present
           :option "dbtype"
           :short "t"
           :type :string
           ;; TODO we can use :spec to provide a spec that would restrict this to the supported options.
           }
          {:as "Name of database"
           :default :present
           :option "dbname"
           :short "d"
           :type :string}
          {:as "Number of queries to run"
           :default 3
           :option "number"
           :short "n"
           :type :int}
          {:as "Query Generator"
           :default :complete-select
           :option "generator"
           :short "g"
           :type :keyword}
          {:as "Show Results?"
           :default false
           :option "show-results"
           :short "v"
           :type :with-flag}
          {:as "Log to SQLite?"
           :default true
           :option "log-to-sqlite"
           :type :with-flag}
          {:as "Run queries that modify the database?"
           :default false
           :option "run-write-queries"
           :type :with-flag}]
   :runs (fn [{:keys [dbtype generator run-write-queries] :as params}]
           (println "params: " params)
           ;; TODO check the choice of generators for existence and safety
           (let [dbtype (clojure.string/lower-case dbtype)
                 generator-obj (get generate/generators generator)]
             (cond
               ;; Check that the platform is supported
               (not (#{"mariadb" "mysql"} dbtype))
               (println dbtype "is not a supported platform.")
               ;; Check that if the query is a write-query that the flag is set
               (and (not (:read-only? generator-obj))
                    (not run-write-queries))
               (println generator "runs queries that may write to the database. To run this, please add the --run-write-queries flag.")
               ;; All checks passed, so run the queries
               :default
               (test-runner (assoc params :dbtype dbtype) generator-obj)
               )))})

(defn -main [& args]
  (run-cmd args CONFIGURATION))
