(ns clj-sac.llm.http.util)

(defn extract-json-from-content [content]
  (let [code-block-re #"(?s)```(?:json)?\s*(\{.*?\})\s*```"
        match (re-find code-block-re content)]
    (if match
      (second match)
      ;; fallback: try to find the first { ... } block
      (let [brace-match (re-find #"(?s)(\{.*\})" content)]
        (second brace-match)))))

(defn extract-clojure-from-content
  "When using keep in mind that evaluating code from a non-deterministc LLM is a security risk."
  [content]
  (when content
    (when-let [match (re-find #"(?s)```clojure\n(.*?)\n```" content)]
      (when match
        (second match)))))

(defn extract-json-from-markdown
  "Extract JSON (object or array) from LLM response, handling markdown code blocks"
  [content]
  (when content
    (let [;; Try to match ```json or ``` code block with array or object
          code-block-re #"(?s)```(?:json)?\s*([{\[].*?[}\]])\s*```"
          match (re-find code-block-re content)]
      (if match
        (second match)
        ;; Fallback: try to find the first {...} or [...] block
        (let [json-match (re-find #"(?s)([{\[].*?[}\]])" content)]
          (second json-match))))))

;; ============================================================================
;; Retry utilities for HTTP calls
;; ============================================================================

(def ^:private default-retryable-statuses
  #{429 ; Too Many Requests
    503 ; Service Unavailable
    504}) ; Gateway Timeout

(defn- calculate-backoff
  "Calculate exponential backoff with jitter.
   Returns sleep time in milliseconds."
  [attempt base-ms jitter?]
  (let [exponential-delay (* base-ms (Math/pow 2 (dec attempt)))
        jitter-factor (if jitter?
                        (+ 0.8 (* 0.4 (rand))) ; 0.8 to 1.2 multiplier
                        1.0)]
    (long (* exponential-delay jitter-factor))))

(defn with-retry
  "Wraps a function that returns an HTTP response map with a simple retry logic.

   The wrapped function should return a map with at least a :status key.

   Options:
   - :max-attempts       - Maximum number of attempts (default: 3)
   - :retryable-statuses - Set of HTTP status codes to retry (default: #{429 502 503 504})
   - :backoff-ms         - Base backoff time in milliseconds (default: 1000)
   - :jitter?            - Add randomness to backoff to prevent thundering herd (default: true)
   - :on-retry           - Optional callback fn called before each retry: (fn [attempt status delay-ms])

   Example usage:
   ```clojure
   (def chat-with-retry
     (with-retry
       #(gemini/chat-completion %1 %2)
       {:max-attempts 5
        :on-retry (fn [attempt status delay]
                    (println \"Retry\" attempt \"after status\" status))}))

   (chat-with-retry model-args http-args)
   ```"
  ([f] (with-retry f {}))
  ([f {:keys [max-attempts retryable-statuses backoff-ms jitter? on-retry]
       :or {max-attempts 3
            retryable-statuses default-retryable-statuses
            backoff-ms 1000
            jitter? true}}]
   (fn [& args]
     (loop [attempt 1]
       (let [result (apply f args)
             status (:status result)]
         (cond
           ;; Success - return result
           (= 200 status)
           result

           ;; Retryable error and attempts remaining
           (and (contains? retryable-statuses status)
                (< attempt max-attempts))
           (let [delay-ms (calculate-backoff attempt backoff-ms jitter?)]
             (when on-retry
               (on-retry attempt status delay-ms))
             (Thread/sleep delay-ms)
             (recur (inc attempt)))

           ;; Non-retryable error or exhausted attempts - return last result
           :else
           result))))))
