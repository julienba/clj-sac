(ns mistral-example
  (:require
   [cheshire.core :as json]
   [clj-sac.llm.http.mistral :as mistral]
   [clojure.core.async :as a]))

(def TOKEN (System/getenv "WF_MISTRAL_KEY"))

(comment
  (mistral/chat-completion
   {:messages [{:content "Write me a small haiku about Amsterdam"
                :role "user"}]
    :model "mistral-large-latest"}
   {:headers {"Authorization" (str "Bearer " TOKEN)}}))


;; ~ Function calling example
;; Transaction data example
(def transaction-data
  {:transactionId ["T1001" "T1002" "T1003" "T1004" "T1005"]
   :customerId ["C001" "C002" "C003" "C002" "C001"]
   :paymentAmount [125.50 89.99 120.00 54.30 210.20]
   :paymentDate ["2021-10-05" "2021-10-06" "2021-10-07" "2021-10-05" "2021-10-08"]
   :paymentStatus ["Paid" "Unpaid" "Paid" "Paid" "Pending"]})

;; Convert column-based data to a sequence of transaction maps
(def transactions
  (mapv (fn [i]
          {:transactionId (get-in transaction-data [:transactionId i])
           :customerId (get-in transaction-data [:customerId i])
           :paymentAmount (get-in transaction-data [:paymentAmount i])
           :paymentDate (get-in transaction-data [:paymentDate i])
           :paymentStatus (get-in transaction-data [:paymentStatus i])})
        (range (count (:transactionId transaction-data)))))

;; Function calling example implementation

;; Define function tools as per the TypeScript example
(def transaction-tools
  [{:type "function"
    :function
    {:name "retrievePaymentStatus"
     :description "Get payment status of a transaction"
     :parameters
     {:type "object"
      :properties
      {:transactionId
       {:type "string"
        :description "The transaction id."}}
      :required ["transactionId"]}}}

   {:type "function"
    :function
    {:name "retrievePaymentDate"
     :description "Get payment date of a transaction"
     :parameters
     {:type "object"
      :properties
      {:transactionId
       {:type "string"
        :description "The transaction id."}}
      :required ["transactionId"]}}}])

(defn retrieve-payment-status [transactions transaction-id]
  (prn :args transaction-id)
  (if-let [transaction (first (filter #(= (:transactionId %) transaction-id) transactions))]
    {:status (:paymentStatus transaction)}
    {:error "transaction id not found."}))

;(retrieve-payment-status transactions "T1001")

(def name->fn
  {:retrievePaymentStatus (fn [args] (retrieve-payment-status transactions (:transactionId args)))
   :retrievePaymentDate (fn [args] (prn "retrievePaymentDate" args))})

(comment
  ;; Example of function calling with transaction query
  (def function-call-response
    (mistral/chat-completion
     {:messages [#_{:role "user"
                    :content "Show me all transactions for customer C001 with status Paid"}
                 {:role "user"
                  :content "What's the status of my transaction T1001?"}]
      :model "mistral-large-latest"
      :tools transaction-tools
      :tool-choice "auto"}
     {:headers {"Authorization" (str "Bearer " TOKEN)}}))

  (tap> function-call-response)

  (def tool-calls (->> (get-in function-call-response [:body :choices 0 :message :tool_calls])
                       (map (fn [tool] (update-in tool [:function :arguments] #(json/parse-string % true))))))

  ; Execute the function
  (def exec-result ((name->fn (-> (first tool-calls) :function :name keyword))
                    (-> (first tool-calls) :function :arguments)))

  ; Call chat-completion again to get the final answer

  (def function-call-response-2
    (mistral/chat-completion
     {:messages [{:role "user"
                  :content (str "What's the status of my transaction T1001?\n"
                                "<retrievePaymentStatus results>"
                                (json/encode exec-result)
                                "</retrievePaymentStatus results>")}]
      :model "mistral-large-latest"
      :tools transaction-tools
      :tool-choice "auto"}
     {:headers {"Authorization" (str "Bearer " TOKEN)}}))

  (tap> function-call-response-2))

;; Example of regular chat completion
(defn regular-chat-example []
  (let [response (mistral/chat-completion
                  {:messages [{:content "Write me a small haiku about Amsterdam"
                               :role "user"}]
                   :model "mistral-large-latest"}
                  {:headers {"Authorization" (str "Bearer " (System/getenv "WF_MISTRAL_KEY"))}})]
    (println "Regular response:")
    (println (:body response))))

;; Example of streaming chat completion (blocking version - shows results immediately)
(defn streaming-chat-example []
  (let [stream-channel (mistral/stream-chat-completion
                        {:messages [{:content "Write me a small haiku about Amsterdam"
                                     :role "user"}]
                         :model "mistral-large-latest"}
                        {:headers {"Authorization" (str "Bearer " (System/getenv "WF_MISTRAL_KEY"))}})]
    (println "Streaming response:")
    ;; Use a blocking loop to see results immediately
    (loop []
      (when-let [chunk (a/<!! stream-channel)]
        (cond
          (= chunk :done)
          (println "\n[Stream complete]")

          (:content chunk)
          (do
            (print (:content chunk))
            (flush)
            (recur))

          (:tool-call chunk)
          (do
            (println "\n[Tool call received:]" (:tool-call chunk))
            (recur))

          (:finish-reason chunk)
          (do
            (println "\n[Finish reason:]" (:finish-reason chunk))
            (recur))

          (:error chunk)
          (println "\n[Error:]" (:error chunk))

          :else
          (recur))))))

;; Example of streaming chat completion (non-blocking version - returns channel)
(defn streaming-chat-example-async []
  (let [stream-channel (mistral/stream-chat-completion
                        {:messages [{:content "Write me a small haiku about Amsterdam"
                                     :role "user"}]
                         :model "mistral-large-latest"}
                        {:headers {"Authorization" (str "Bearer " (System/getenv "WF_MISTRAL_KEY"))}})]
    (println "Streaming response (async):")
    (a/go-loop []
      (when-let [chunk (a/<! stream-channel)]
        (cond
          (= chunk :done)
          (println "\n[Stream complete]")

          (:content chunk)
          (do
            (print (:content chunk))
            (flush)
            (recur))

          (:tool-call chunk)
          (do
            (println "\n[Tool call received:]" (:tool-call chunk))
            (recur))

          (:finish-reason chunk)
          (do
            (println "\n[Finish reason:]" (:finish-reason chunk))
            (recur))

          (:error chunk)
          (println "\n[Error:]" (:error chunk))

          :else
          (recur))))
    stream-channel))

;; Example of streaming with tool calls
(defn streaming-with-tools-example []
  (let [tools [{:type "function"
                :function {:name "get_weather"
                           :description "Get the current weather in a given location"
                           :parameters {:type "object"
                                        :properties {:location {:type "string"
                                                                :description "The city and state, e.g. San Francisco, CA"}}
                                        :required ["location"]}}}]
        stream-channel (mistral/stream-chat-completion
                        {:messages [{:content "What's the weather like in Amsterdam?"
                                     :role "user"}]
                         :model "mistral-large-latest"
                         :tools tools
                         :tool-choice "auto"}
                        {:headers {"Authorization" (str "Bearer " (System/getenv "WF_MISTRAL_KEY"))}})]
    (println "Streaming with tools:")
    (a/go-loop []
      (when-let [chunk (a/<! stream-channel)]
        (cond
          (= chunk :done)
          (println "\n[Stream complete]")

          (:content chunk)
          (do
            (print (:content chunk))
            (flush)
            (recur))

          (:tool-call chunk)
          (do
            (println "\n[Tool call:]" (pr-str (:tool-call chunk)))
            (recur))

          (:finish-reason chunk)
          (do
            (println "\n[Finish reason:]" (:finish-reason chunk))
            (recur))

          (:error chunk)
          (println "\n[Error:]" (:error chunk))

          :else
          (recur))))))

(comment
  ;; Run examples (make sure WF_MISTRAL_KEY is set)
  (regular-chat-example)

  ;; Blocking version - shows results immediately
  (streaming-chat-example)

  ;; Non-blocking version - returns a channel, runs in background
  (streaming-chat-example-async)

  (streaming-with-tools-example))

