(ns chaining-example
  (:require [cheshire.core :as json]
            [clojure.core.async :as a :refer [go <! <!!]]
            [clj-sac.llm.http.mistral :as mistral]
            [clj-sac.llm.http.util :as util]
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

(defn retry-async
  "Retries an async operation (fn returning a channel) n times with backoff.
   - operation-fn: a function that takes no args and returns a channel
   - retries: max number of retries
   - delay-ms: time to wait between retries"
  [operation-fn {:keys [retries delay-ms] :or {retries 3 delay-ms 1000}}]
  (go
    (loop [attempt 1]
      (let [result (<! (operation-fn))]
        (prn :result result)
        ;; Check if result is an Exception or a failed HTTP status (optional)
        (if (and (:error result)
                 (= 429 (-> result :value :status))
                 (< attempt retries))
          (do
            (println "Attempt" attempt "failed. Retrying in" delay-ms "ms...")
            (<! (a/timeout delay-ms)) ;; Non-blocking wait
            (recur (inc attempt)))
          result)))))

(def TOKEN (System/getenv "WF_MISTRAL_KEY"))

(defn async-llm-call [prompt-content]
  (mistral/chat-completion
   {:messages [{:content prompt-content
                :role "user"}]
    :model "mistral-small-latest"}
   {:async? true
    :headers {"Authorization" (str "Bearer " TOKEN)}}))

(defn async-llm-call2 [messages]
  (retry-async
   #(mistral/chat-completion
     {:messages messages
      :model "mistral-small-latest"}
     {:async? true
      :headers {"Authorization" (str "Bearer " TOKEN)}})
   {:retries 3 :delay-ms (* 120 1000)}))

(defn async-http-call [_ctx]
  (retry-async
   #(java-future->chan
     (hc/post "http://lean-news.tech/health"
              {:async? true
               :accept :json
               :form-params {:query "Hello"}}))
   {:retries 3 :delay-ms 1000}))

#_(go
  (let [resp (<! (async-llm-call "Write me a small haiku about Amsterdam"))]
    (prn :resp resp)))

;; TODO: reuse the result of the first response in the second
;; Make a version that use Mistral
;; Make a version that use Mistral with SSE

(defn execute-pipeline
  "Executes a list of async steps sequentially.
   - initial-state: Data passed to the first step.
   - steps: A list of functions. Each fn must accept `state` and return a channel.

   Returns a channel with the final state or the first Exception encountered."
  [initial-state steps]
  (go
    (loop [current-state initial-state
           [step-fn & remaining] steps]
      (if-not step-fn
        current-state ;; No more steps, return final result
        (let [;; Run the current step with retries
              ;;result (<! (retry-async #(step-fn current-state) {}))
              result (<! (step-fn current-state))
              ]

          (if (:error result)
            result ;; Short-circuit: Stop chain and return the error
            (recur result remaining)))))))

(defn run-flow []
  (go
    (let [initial-context {}
          steps [(fn [ctx]
                   ; TODO store result in ctx
                   (go
                     (let [result (<! (async-llm-call2 [{:content "Write me a small haiku about Amsterdam"
                                                         :role "user"}]))]
                       (prn :haiku-result result)
                       (assoc ctx :haiku-result result))))
                 #_(fn [ctx]
                   (prn :ctx2 ctx)
                   #_(async-llm-call :both-prompt))]
        ;;   steps [(fn [ctx] (go (let [response (<! (async-http-call {}))]
        ;;                          (prn :response response)
        ;;                          (assoc ctx :response response))))
        ;;          #_(fn [ctx] (prn ::ctx ctx))]
          final-result (<! (execute-pipeline initial-context steps))]
      final-result)))

#_(time
 (go
   (let [flow-result (<!! (run-flow))]
     (prn :flow-result flow-result))))

(time
 (let [ch (run-flow)
       timeout-ch (a/timeout (* 60 1000))
       [result port] (a/alts!! [ch timeout-ch])]

   (if (= port timeout-ch)
     (println "Timed out! API took too long.")
     (println "Success:" result))))

(+ 1 2)


;; (defn run-run-llm-flow []
;;   (go
;;     (let [initial-context {:original-query "Hello World"}
;;           steps           [step-1-initial-call ; function that take the ctx has first argument
;;                            step-2-refine-call]

;;           final-result    (<! (execute-pipeline initial-context steps))]

;;       (if (instance? Exception final-result)
;;         (println "Pipeline failed:" (.getMessage final-result))
;;         (println "Success! Final ID:" (:gemini-id final-result))))))


; "Write me a small haiku about Amsterdam"



(def result (atom nil))
(defn chain-mistral []
  (go
    (let [resp1 (<! (retry-async
                     #(async-llm-call "Write me a small haiku about Amsterdam")
                     {:retries 3 :delay-ms (* 120 1000)}
                     ))]
      (println "after call: " resp1)
      ; TODO
      ; - check for the response to good
      ; - extract the response
      ; - reuse the response to send it to the second call
      (reset! result resp1))))

;(chain-mistral)

#_(defn chain-fns []
  (println "Start")
  (go
    (let [resp-1 (<! (retry-async
                      #(java-future->chan
                        (hc/post "http://lean-news.tech/health"
                                 {:async? true
                                  :accept :json
                                  :form-params {:query "Hello"}}))
                      {:retries 3 :delay-ms 1000}))]
      (when (= 200 (:status resp-1))
        ; TODO I need to extract the value of the first call
        (let [resp-2 (<! (retry-async
                          #(java-future->chan
                            (hc/post "http://lean-news.tech/health"
                                     {:async? true
                                      :accept :json
                                      :form-params {:query "Hello"}}))
                          {:retries 3 :delay-ms 1000}))])))))



;(chain-fns)
#_(comment
  ;; Gemini brain fart
  (defn call-gemini-chain []
    (go
      (println "Step 1: Calling Gemini Initial API...")

      ;; --- CALL 1 ---
      (let [resp-1 (<! (retry-async
                        #(java-future->chan
                          (hc/post "https://api.gemini.com/v1/initial"
                                   {:async? true
                                    :accept :json
                                    :form-params {:query "Hello"}}))
                        {:retries 3 :delay-ms 500}))]

        (if (instance? Exception resp-1)
          (println "Chain failed at Step 1:" (.getMessage resp-1))

          ;; Success! Extract data for the next call
          (let [context-id (-> resp-1 :body :id)]
            (println "Step 1 Success. Got Context ID:" context-id)
            (println "Step 2: Calling Gemini with Context...")

            ;; --- CALL 2 (Reusing data from Call 1) ---
            (let [resp-2 (<! (retry-async
                              #(java-future->chan
                                (hc/post "https://api.gemini.com/v1/refine"
                                         {:async? true
                                          :accept :json
                                          :form-params {:context context-id
                                                        :prompt "Refine this"}}))
                              {:retries 3 :delay-ms 500}))]

              (if (instance? Exception resp-2)
                (println "Chain failed at Step 2:" (.getMessage resp-2))
                (println "Chain Complete! Final Result:" (:body resp-2))))))))))

;; ---------

(require '[clojure.core.async.flow :as flow])

(def graph
  {:request-1
   {:run (fn [state inputs]
           ;; You must manually return a channel here
           (prn :req-1 state inputs)
           (let [res (async-http-call {})]
             res))
    :out [:gemini-id]} ;; Declare what this unit outputs

   :request-2
   {:deps [:request-1] ;; Declare dependency explicitly
    :run (fn [state {:keys [request-1]}] ;; Receive inputs from previous node
           (prn :req-2 state request-1)
           #_(let [id (-> request-1 :gemini-id)]
             (future->chan (hc/post "..." {:form-params {:id id}})))
           (let [res (async-http-call {})]
             res))
    :out [:final-result]}})

(go
  (let [result-chan (flow/start graph)]
    (<! result-chan)))