;;   Copyright (c) Rich Hickey and contributors. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns ^{:skip-wiki true}
    clojure.core.async.impl.timers2
  (:require [clojure.core.async.impl.protocols :as impl]
            [clojure.core.async.impl.dispatch :as dispatch])
  (:import [java.util.concurrent DelayQueue Delayed TimeUnit]
           [java.util.concurrent.locks Lock]))

(set! *warn-on-reflection* true)

(defonce ^:private ^DelayQueue timeouts-queue
  (DelayQueue.))

(deftype TimeoutQueueEntry [^long timestamp action]
  Delayed
  (getDelay [this time-unit]
    (.convert time-unit (- timestamp (System/currentTimeMillis)) TimeUnit/MILLISECONDS))
  (compareTo
   [this other]
   (let [ostamp (.timestamp ^TimeoutQueueEntry other)]
     (if (< timestamp ostamp)
       -1
       (if (== timestamp ostamp)
         0
         1))))
  impl/Channel
  (close! [this]
    (action)))

(defn- timeout-worker
  []
  (let [q timeouts-queue]
    (loop []
      (let [^TimeoutQueueEntry tqe (.take q)]
        (impl/close! tqe))
      (recur))))

(defonce timeout-daemon
  (delay
   (doto (Thread. ^Runnable timeout-worker "clojure.core.async.timers2/timeout-daemon")
     (.setDaemon true)
     (.start))))

(defn timeout [ms]
  @timeout-daemon
  (let [start (System/nanoTime)] ; only use nano time to get the
                                 ; relative diff between creation and
                                 ; handler registering
    (reify
      impl/ReadPort
      (take! [port fn1-handler]
        (let [now (System/nanoTime)
              delay (long (- ms (/ (- now start) 1e6)))
              ^Callable action (fn []
                                 (.lock ^Lock fn1-handler)
                                 (if-let [good (and (impl/active? fn1-handler)
                                                    (impl/commit fn1-handler))]
                                   (do
                                     (.unlock ^Lock fn1-handler)
                                     (dispatch/run (fn [] (good nil))))
                                   (.unlock ^Lock fn1-handler)))]
          (if (pos? delay)
            (let [entry (TimeoutQueueEntry. (+ (System/currentTimeMillis) delay) action)
                  _ (.put timeouts-queue entry)]
              (when (satisfies? impl/NackableHandler fn1-handler)
                (let [cancel (fn [id]
                               (when-not (identical? fn1-handler id)
                                 (.remove timeouts-queue entry)))]
                  (when-some [result (impl/take! (impl/nack-channel fn1-handler)
                                                 (reify
                                                   Lock
                                                   (lock [_])
                                                   (unlock [_])
                                                   impl/Handler
                                                   (active? [h] true)
                                                   (blockable? [h] true)
                                                   (lock-id [h] 0)
                                                   (commit [h] cancel)))]
                    (cancel @result)))))
            (action)))
        nil)
      impl/Channel
      (close! [chan]
        (throw (IllegalArgumentException. "can't close timeout")))
      (closed? [chan]
        (not (pos? (- (System/nanoTime) start)))))))
