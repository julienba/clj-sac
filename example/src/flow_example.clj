(ns flow-example
  (:require [clojure.core.async :as a]
            [clojure.core.async.flow :as flow]
            [hato.client :as hc])
  (:import (java.util.concurrent CompletableFuture)))


(defn java-future->chan
  "Converts a CompletableFuture to a core.async channel.
   Returns a channel that will receive the result or the exception."
  [^CompletableFuture cf]
  (let [c (a/chan 1)]
    (.whenComplete cf
                   (reify java.util.function.BiConsumer
                     (accept [_ result exception]
                       (prn ::complete result exception)
                       (a/put! c (if result
                                   [:result result]
                                   [:error {:exception exception}])))))
    c))

;; --- Helper: Blocking Retry (since flow steps run in a thread pool) ---
(defn http-retry-blocking [req-fn]
  (loop [attempt 1]
    (let [resp (try (req-fn) (catch Exception e e))]
      (if (and (:error resp) (< attempt 3))
        (do (Thread/sleep 500) (recur (inc attempt)))
        resp))))


(defn async-http-call [_ctx]
  (http-retry-blocking
   #(java-future->chan
     (hc/post "http://lean-news.tech/health"
              {:async? true
               :accept :json
               :form-params {:query "Hello"}}))
   #_{:retries 3 :delay-ms 1000}))

;; --- Process 1: Initial Call ---
(def api-1-proc
  {:describe (fn []
               {:workload :io ;; Important: Tells flow this is blocking I/O
                :ins {:in "Initial Query"}
                :outs {:out "Context ID"}})

   :transform (fn [state _ query]
                (prn :query query)
                (let [resp (http-retry-blocking #(async-http-call {}))]
                  (prn :resp resp)
                  (if (:error resp)
                    [state nil] ;; Stop flow on error (or output to separate error port)
                    ;; Success: Pass ID to the next node
                    [state {:out [(-> resp :body :id)]}])))

   ;; Required: Initialize state (unused here)
   :init (fn [_] nil)})

;; --- Process 2: Refine Call ---
;; Reads from :in (which comes from api-1), writes to :result
(def api-2-proc
  {:describe (fn []
               {:workload :io
                :ins {:in "Context ID"}
                :outs {:result "Final Output"}})

   :transform (fn [state _ context-id]
                (let [resp (http-retry-blocking #(async-http-call {}))]
                  (if (instance? Exception resp)
                    [state nil]
                    ;; Success: Output final result
                    [state {:result [(:body resp)]}])))

   :init (fn [_] nil)})

;; --- The Flow Topology ---
(def gemini-flow-def
  {:procs {:step-1 {:proc (flow/process #'api-1-proc)}
           :step-2 {:proc (flow/process #'api-2-proc)}}

   :conns [[[:step-1 :out] [:step-2 :in]]]})

;; --- Running the Flow ---
(defn run-flow []
  (let [flow (flow/create-flow gemini-flow-def)]
    ;; Start returns a map with :report-chan and :error-chan
    (flow/start flow)

    (flow/resume flow)

    ;; Inject the starting data into Step 1
    (flow/inject flow [:step-1 :in] ["Hello Gemini"])

    flow))

(def flow (flow/create-flow gemini-flow-def))


