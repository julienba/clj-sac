(ns chaining-example
  (:require [cheshire.core :as json]
            [clojure.core.async :as a :refer [go <! >!]]
            [clj-sac.llm.http.mistral :as mistral]
            [clj-sac.llm.http.util :as util]
            [hato.client :as hc])
  (:import (java.util.concurrent CompletableFuture)))

;; (defn java-future->chan
;;   "Converts a CompletableFuture to a core.async channel.
;;    Returns a channel that will receive the result or the exception."
;;   [^CompletableFuture cf]
;;   (let [c (a/chan 1)]
;;     (.whenComplete cf
;;                    (reify java.util.function.BiConsumer
;;                      (accept [_ result exception]
;;                        (prn ::complete result exception)
;;                        (a/put! c (if result
;;                                    [:result result]
;;                                    [:error {:exception exception}])))))
;;     c))

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

#_(go
  (let [resp (<! (async-llm-call "Write me a small haiku about Amsterdam"))]
    (prn :resp resp)))

;; TODO: reuse the result of the first response in the second
;; Make a version that use Mistral
;; Make a version that use Mistral with SSE


; "Write me a small haiku about Amsterdam"

(def result (atom nil))
@result
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

#_(defn call-gemini-chain []
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
              (println "Chain Complete! Final Result:" (:body resp-2)))))))))