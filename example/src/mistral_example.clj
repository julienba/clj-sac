(ns mistral-example
  (:require
   [cheshire.core :as json]
   [clj-sac.llm.http.mistral :as mistral]))

(def TOKEN (System/getenv "WF_MISTRAL_KEY"))

(comment
  (mistral/chat-completion
   {:messages [{:content "Write me a small haiku about Amsterdam"
                :role "user"}]
    :model "mistral-large-latest"}
   {:headers {"Authorization" (str "Bearer " TOKEN)}})
  )


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

