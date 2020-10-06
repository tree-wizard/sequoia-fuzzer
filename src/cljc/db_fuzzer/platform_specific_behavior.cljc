(ns db-fuzzer.platform-specific-behavior
  (:require [clojure.test :refer [deftest is run-tests] :as test]))

(defn major-version [semver]
  (when-let [major-as-string (second (re-matches #"(\d+).*" semver))]
    (clojure.edn/read-string major-as-string)))

(deftest test-major-version
  (is (= (major-version "1.2.23") 1))
  (is (= (major-version "foo") nil))
  (is (= (major-version "8.0.21-cluster") 8)))

(defn supports-json-datatype? [platform version-string]
  (and (contains? #{"mysql"} platform)
       (>= (or (major-version version-string) 0) 8)))

(deftest test-supports-json-datatype
  (is (= (supports-json-datatype? "mysql" "8.0.21-cluster") true))
  (is (= (supports-json-datatype? "mysql" "5.0.21-cluster") false))
  (is (= (supports-json-datatype? "oracle" "100000") false))
  (is (= (supports-json-datatype? "mysql" "I-dont-have-a-valid-version") false)))
