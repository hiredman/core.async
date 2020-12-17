(ns clojure.core.async.timers2-test
  (:require [clojure.test :refer :all]
            [clojure.core.async.impl.timers2 :refer :all]
            [clojure.core.async :as async]))

(deftest timeout-nackable
  (async/alt!!
    (async/timeout 100000) ([_]
                            (is false))
    (async/timeout 10) ([_]
                        (is true)))
  (is (zero? (count @#'clojure.core.async.impl.timers2/timeouts-queue))))
