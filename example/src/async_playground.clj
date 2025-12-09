(ns async-playground
  (:require [clojure.core.async :as a :refer [go go-loop >! <! put! chan promise-chan close!]])
  (:import [java.util.concurrent CompletableFuture]))


(defn future->chan
  "Returns a promise-channel that will contain the result of the
   CompletableFuture when it completes."
  [^CompletableFuture fut]
  (let [c (promise-chan)]
    ;; We attach a callback to the Future using .handle
    ;; This allows us to handle both success and failure (exceptions)
    (.handle fut
             (reify java.util.function.BiFunction
               (apply [_ result exception]
                 (cond
                   exception (put! c [:error exception])
                   (nil? result) (close! c)
                   :else (put! c [:value result])))))
    c))

;; (defn mock-java-api []
;;   (CompletableFuture/supplyAsync
;;    (reify java.util.function.Supplier
;;      (get [_]
;;        (Thread/sleep 3000) ;; Simulate work
;;        "Data from Java"))))

;; (let [java-future (mock-java-api)       ;; 1. Get the Future
;;       result-ch (future->chan java-future)] ;; 2. Bridge it
;;   (go
;;     (println "Waiting for Java...")
;;     (let [val (<! result-ch)]        ;; 3. Park and wait (non-blocking)
;;       (println "Received:" val))))

;; ~ --------------------------

(defn run-with-retry
  "Executes a task-fn (which returns a CompletableFuture).
   Retries up to max-retries times if the future completes exceptionally.
   Returns a channel containing the final success or the last error."
  [task-fn input max-retries]
  (go-loop [attempt 1]
    ;; 1. Call the user's function to get the Future
    (let [fut (task-fn input)
          ;; 2. Convert to channel
          result (<! (future->chan fut))]

      (cond
        ;; Case A: Success - return the value
        (not (instance? Throwable result))
        [:value result]

        ;; Case B: Failure, but we have retries left
        (< attempt max-retries)
        (do
          (println (str "Attempt " attempt " failed: " (.getMessage result) ". Retrying..."))
          (<! (a/timeout 500)) ;; Optional backoff delay
          (recur (inc attempt)))

        ;; Case C: Failure, no retries left
        :else
        (do
          (println "All retries exhausted.")
          [:error {:msg "Out of retry" :result result}])))))

(defn chain-futures
  "Takes an initial value and a sequence of functions.
   Each function must accept a value and return a CompletableFuture.
   Stops and returns the error if any step fails (after its retries)."
  [initial-ctx task-fns]
  (go-loop [current-ctx initial-ctx
            [next-task & remaining-tasks] task-fns]
    ;; If no tasks left, return the final value
    (if-not next-task
      current-ctx

      ;; Execute the current task with retries
      (let [[retry-status [status result]] (<! (run-with-retry (:fn next-task) current-ctx 3))]
        (prn ::chain result)
        (if (or (= :error status)
                (= :error retry-status))
          ;; If it failed (even after retries), short-circuit and return error
          [:error {:result result :ctx current-ctx}]
          ;; If success, feed result into the next task
          (recur (assoc-in current-ctx [:steps :name (:id next-task)] result)
                 remaining-tasks))))))

(defn login-service [_ctx]
  (CompletableFuture/supplyAsync
   (reify java.util.function.Supplier
     (get [_]
       (println "Step 1: Logging in...")
       (Thread/sleep 200)
       {:user-id 101}))))

(defn fetch-profile-service [ctx]
  (CompletableFuture/supplyAsync
   (reify java.util.function.Supplier
     (get [_]
       (prn :profil-ctx (-> ctx :steps :login :user-id))
       (let [uid 123 #_(:user-id user-data)]
         (println "Step 2: Fetching profile for" uid "...")
         ;; Simulate 10% failure rate
         (if false #_(> (rand) 0.1)
             (throw (RuntimeException. "Network Blip!"))
             {:user-id uid :name "Gemini" :role :admin}))))))

(defn db-save-service [ctx]
  (CompletableFuture/supplyAsync
   (reify java.util.function.Supplier
     (get [_]
          (println "Step 3: Saving" (-> ctx :steps :profile :name) "to DB...")
          (Thread/sleep 200)
          {}))))

(defn run-demo []
  (let [workflow [{:id :login :fn login-service}
                  {:id :profile :fn fetch-profile-service}
                  {:id :save :fn db-save-service}]]

    (go
      (let [working-ch (chain-futures {} workflow)
            timeout-ch (a/timeout (* 120 1000))
            [val port] (a/alts! [working-ch timeout-ch])]
        (println "------------------- " port)
        (if (= port timeout-ch)
          (println "Timed out! API took too long.")
          (if (:error val)
            (println "Workflow Failed:" val)
            (println "Workflow Success!" val)))))))

#_(defn execute-with-global-timeout
  "Executes the task chain, but aborts if the total time exceeds timeout-ms."
  [initial-val task-fns timeout-ms]
  (go
    (let [work-ch (chain-futures initial-val task-fns)
          timeout-ch (timeout timeout-ms)
          ;; alts! waits for the FIRST channel to return a value
          [val port] (alts! [work-ch timeout-ch])]

      (if (= port work-ch)
        ;; Case 1: Work finished before timeout
        val

        ;; Case 2: Timeout finished before work
        (let [msg (str "Global timeout of " timeout-ms "ms exceeded.")]
          (ex-info msg {:type :timeout}))))))

;; Run it
(comment
  (run-demo))
