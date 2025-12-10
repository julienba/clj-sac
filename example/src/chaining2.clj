(ns chaining2
  (:require [clj-sac.llm.http.mistral :as mistral]
            [clojure.core.async :as a :refer [go go-loop <!]]))

;; ~ Helpers ----------------------------------------

(defn run-with-retry
  "Executes a task-fn (which returns a CompletableFuture).
   Retries up to max-retries times if the future completes exceptionally.
   Returns a channel containing the final success or the last error."
  [task-fn input max-retries]
  (go-loop [attempt 1]
    ;; 1. Call the user's function to get the Future
    (let [fut (task-fn input)
          result (<! fut)]
      (cond
        ;; Case A: Success - return the value
        (= :value (first result))
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
        (if (or (= :error status)
                (= :error retry-status))
          ;; If it failed (even after retries), short-circuit and return error
          [:error {:result result :ctx current-ctx}]
          ;; If success, feed result into the context
          (recur (assoc-in current-ctx [:steps :id (:id next-task)] result)
                 remaining-tasks))))))

;; ~ Main --------------------------------------------

(def TOKEN (System/getenv "WF_MISTRAL_KEY"))

(defn async-llm-call [messages]
  (prn :async-llm-call)
  (mistral/chat-completion
   {:messages messages
    :model "mistral-small-latest"}
   {:async? true
    :headers {"Authorization" (str "Bearer " TOKEN)}}))

(defn run-demo []
  (let [workflow [{:id :haiku
                   :fn (fn [_ctx]
                         (async-llm-call [{:content "Write an Haiku about Amsterdam"
                                           :role "user"}]))}
                  {:id :translate
                   :fn (fn [ctx]
                         ; In production some error management would be good
                         (let [prev-result (get-in ctx [:steps :name :haiku :body :choices 0 :message :content])]
                           (async-llm-call [{:content "Translate in french"
                                             :role "system"}
                                            {:content prev-result
                                             :role "user"}])))}]]
    (go
      (let [working-ch (chain-futures {} workflow)
            timeout-ch (a/timeout (* 2 60 1000))
            [val port] (a/alts! [working-ch timeout-ch])]
        (println "------------------- ")
        (def VAL val)
        (if (= port timeout-ch)
          (println "Timed out! API took too long.")
          (if (:error val)
            (println "Workflow Failed:" val)
            (println "Workflow Success!" val)))))))


(comment
  ;; Sequence LLM call
  (run-demo))



(comment
  ;; Single LLM call
  (go
    (let [resp (<! (async-llm-call [{:content "Write me a small haiku about Amsterdam"
                                     :role "user"}]))]
      (println :response resp)
      ))
  )
