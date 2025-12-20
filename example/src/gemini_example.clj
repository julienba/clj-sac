(ns gemini-example
  (:require [clj-sac.llm.http.gemini :as gemini]
            [clj-sac.llm.http.util :as http.util]))

(def TOKEN (System/getenv "GEMINI_API_KEY"))

;; Basic example - no retry
(comment
  (def answer (gemini/chat-completion
               {:messages [{:content "Write me a small haiku about Amsterdam"
                            :role "user"}]
                :model "gemini-2.5-flash"}
               {:headers {"x-goog-api-key" TOKEN}}))
  (get-in answer [:body :candidates 0 :content :parts 0 :text])
  ;; => "Canals gently flow,\nBicycles glide on the bridges,\nDutch charm fills the air."
  )

;; ============================================================================
;; Using with-retry for basic resilience to platform overload
;; ============================================================================

;; Create a wrapped function with retry logic (recommended approach)
(def chat-with-retry
  (http.util/with-retry
    #(gemini/chat-completion %1 %2)
    {:max-attempts 3
     ;; Good place to integrate with a tracing system or a persistant workflow if you have any
     :on-retry (fn [attempt status delay-ms]
                 (println (format "Attempt %d failed with status %d. Retrying in %dms..."
                                  attempt status delay-ms)))}))

(comment
  (defn log-retry [{:keys [response]}]
    (prn "Retry after: " response))

  ;; Use the wrapped function - retries automatically on 429, 503 and 504
  (def answer (chat-with-retry
               {:messages [{:content "Write me a small haiku about Amsterdam"
                            :role "user"}]
                :model "gemini-2.5-flash"}
               {:headers {"x-goog-api-key" TOKEN}}))

  (get-in answer [:body :candidates 0 :content :parts 0 :text])
  ;; => "Canals gently flow,\nBicycles glide on the bridges,\nDutch charm fills the air."
  )

