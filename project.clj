(defproject db-fuzzer "0.1.0-SNAPSHOT"
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [org.clojure/clojurescript "1.10.773"]
                 [javax.xml.bind/jaxb-api "2.2.11"]
                 [reagent "1.0.0-alpha2"]
                 [seancorfield/next.jdbc "1.1.569"]
                 [org.mariadb.jdbc/mariadb-java-client "2.6.2"]
                 [mysql/mysql-connector-java "8.0.21"]
                 [honeysql "1.0.444"]
                 [devcards "0.2.7" :exclusions [cljsjs/react]]
                 [org.clojure/test.check "1.1.0"]
                 [cli-matic "0.4.3"]
                 [try-let "1.3.1"]
                 [instaparse "1.4.10"]
                 [rhizome "0.2.9"] ;; so that instaparse/visualize works
                 [org.clojure/java.jdbc "0.7.11"]
                 [org.xerial/sqlite-jdbc "3.32.3.2"]
                 [ch.gluet/couplet "0.2.1"]]

  :min-lein-version "2.5.3"

  :source-paths ["src/clj" "src/cljc"]
  :main db-fuzzer.core

  :plugins [[lein-cljsbuild "1.1.4"]]

  :clean-targets ^{:protect false} ["resources/public/js"
                                    "target"]

  :figwheel {:css-dirs ["resources/public/css"]}


;  :repl-options {:nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}

  :profiles
  {:dev
   {:dependencies [
                   [figwheel-sidecar "0.5.15"]
                   [com.cemerick/piggieback "0.2.1"]]

    :plugins      [[lein-figwheel "0.5.15"]]
    }
   :uberjar
   {:aot :all
    :main db-fuzzer.core
    }}

  :cljsbuild
  {:builds
   [{:id           "dev"
     :source-paths ["src/cljs"]
     :figwheel     {:on-jsload "db-fuzzer.core/reload"}
     :compiler     {:main                 db-fuzzer.core
                    :optimizations        :none
                    :output-to            "resources/public/js/app.js"
                    :output-dir           "resources/public/js/dev"
                    :asset-path           "js/dev"
                    :source-map-timestamp true}}

    {:id           "devcards"
     :source-paths ["src/devcards" "src/cljs"]
     :figwheel     {:devcards true}
     :compiler     {:main                 "db-fuzzer.core-card"
                    :optimizations        :none
                    :output-to            "resources/public/js/devcards.js"
                    :output-dir           "resources/public/js/devcards"
                    :asset-path           "js/devcards"
                    :source-map-timestamp true}}

    {:id           "hostedcards"
     :source-paths ["src/devcards" "src/cljs"]
     :compiler     {:main          "db-fuzzer.core-card"
                    :optimizations :advanced
                    :devcards      true
                    :output-to     "resources/public/js/devcards.js"
                    :output-dir    "resources/public/js/hostedcards"}}

    {:id           "min"
     :source-paths ["src/cljs"]
     :compiler     {:main            db-fuzzer.core
                    :optimizations   :advanced
                    :output-to       "resources/public/js/app.js"
                    :output-dir      "resources/public/js/min"
                    :elide-asserts   true
                    :closure-defines {goog.DEBUG false}
                    :pretty-print    false}}

    ]})
