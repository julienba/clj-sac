(ns clj-sac.agent
  "Naive implementation of an agent.
   For educational purpose"
  (:require [clj-sac.llm.http.mistral :as mistral]
            [clj-sac.tool :as tool]
            [clojure.data.json :as json]
            [clojure.tools.logging :as log]))

;; OODA Loop Implementation
(defprotocol OODALoop
  (observe [this] "Gather information from environment")
  (orient [this observations] "Analyze and synthesize observations")
  (decide [this situation] "Determine the best course of action")
  (act [this decision] "Execute the decision"))

(defn decide-impl
  "Get LLM response with potential tool calls"
  [model situation]
  (log/info :decide-impl situation)
  (when (:ready-for-inference situation)
    (try
      (let [response (mistral/chat-completion
                      {:messages (:conversation situation)
                       :model model
                       :tools tool/tools
                       :temperature 0.7}
                      {:headers {"Authorization" (str "Bearer " mistral/TOKEN)}})]
        (if (= 200 (:status response))
          {:type :llm-response
           :response (:body response)
           :conversation (:conversation situation)}
          {:type :error
           :error (str "HTTP " (:status response) " error")}))
      (catch Exception e
        {:type :error
         :error (if (instance? clojure.lang.ExceptionInfo e)
                  (ex-data e)
                  (.getMessage e))}))))

(defn act-impl
  "Act: Process LLM response and execute any tool calls"
  [decision conversation]
  (case (:type decision)
    :llm-response
    (let [response (:response decision)
          message (first (:choices response))
          assistant-message (:message message)
          tool-calls (:tool_calls assistant-message)]

      (if tool-calls
        ;; Execute tool calls
        (let [tool-results (for [tool-call tool-calls]
                             (let [function (:function tool-call)
                                   tool-name (:name function)
                                   args (json/read-str (:arguments function) :key-fn keyword)
                                   result (tool/execute-tool tool-name args)]
                               {:tool_call_id (:id tool-call)
                                :result result}))

              ;; Add assistant message to conversation
              updated-conversation (conj (:conversation decision) assistant-message)

              ;; Add tool results as tool messages
              final-conversation (reduce (fn [conv tool-result]
                                           (conj conv {:role "tool"
                                                       :tool_call_id (:tool_call_id tool-result)
                                                       :content (json/write-str (:result tool-result))}))
                                         updated-conversation
                                         tool-results)]

          ;; Print tool execution summary
          (doseq [tool-result tool-results]
            (let [result (:result tool-result)]
              (if (:success result)
                (log/info (str "Tool executed successfully: "
                               (or (:message result) "Operation completed")))
                (log/error (str "Tool error: " (:error result))))))

          ;; Return updated state for next iteration
          {:conversation final-conversation
           :continue true})

        ;; No tool calls - just add assistant response and print it
        (do
          (log/info (str "LLM: " (:content assistant-message)))
          {:conversation (conj (:conversation decision) assistant-message)
           :continue true})))

    :error
    (do
      (log/error (str "Error: " (:error decision)))
      {:conversation (:conversation decision)
       :continue true})

    ;; Default case
    {:conversation conversation
     :continue false}))

(defrecord Agent [conversation model user-prompt max-iterations max-memory]
  OODALoop

  (observe [_this]
    ;; Observe: Get user input or continue with existing conversation
    (if (and user-prompt (empty? (rest conversation)))
      ;; Only use user-prompt if this is the first iteration (only system message exists)
      (do
        (log/info :line user-prompt)
        {:type :user-input
         :content user-prompt
         :timestamp (System/currentTimeMillis)})
      ;; Continue with conversation if the last message is a tool message
      (when (and (seq conversation)
                 (= (:role (last conversation)) "tool"))
        {:type :continue-conversation
         :timestamp (System/currentTimeMillis)})))

  (orient [_this observations]
    ;; Orient: Add user input to conversation and prepare for LLM
    (when observations
      (case (:type observations)
        :user-input
        (let [user-message {:role "user" :content (:content observations)}
              updated-conversation (conj conversation user-message)]
          {:conversation updated-conversation
           :ready-for-inference true})
        :continue-conversation
        {:conversation conversation
         :ready-for-inference true})))

  (decide [_this situation]
    (decide-impl model situation))

  (act [_this decision]
    (act-impl decision conversation)))

(def default-system-prompt
  "You are a helpful coding assistant with access to file operations.
   When you need to read files, edit files, or list directories, use the available tools.
   Always explain what you're doing before using tools.
   For file edits, be precise with the old_str parameter - it must match exactly what's in the file.")

(defn truncate-conversation
  "Keep only the last max-memory messages in the conversation, preserving the system message"
  [conversation max-memory]
  (if (<= (count conversation) max-memory)
    conversation
    (let [system-message (first conversation)
          other-messages (rest conversation)
          messages-to-keep (take-last (dec max-memory) other-messages)]
      (cons system-message messages-to-keep))))

(defn create-agent
  "Create a new OODA loop agent with initial system message"
  [model system-prompt user-prompt]
  (let [system-message {:role "system"
                        :content system-prompt}]
    (->Agent [system-message] model user-prompt 50 10)))

(defn run-ooda-loop
  "Main OODA loop execution"
  [agent]
  (log/info "OODA Loop Agent started (Ctrl+C to quit)")
  (log/info "Using Mistral model for decisions")
  (log/info (str "Max iterations: " (:max-iterations agent)))
  (log/info (str "Max memory: " (:max-memory agent) " messages"))
  (loop [current-agent agent
         iteration 0]
    (Thread/sleep 1000) ; Slow things down in case to prevent a infinit loop to spam the LLM provider

    ;; Check iteration limit
    (if (>= iteration (:max-iterations current-agent))
      (do
        (log/warn (str "Reached maximum iterations (" (:max-iterations current-agent) "). Stopping."))
        nil)
      ;; OBSERVE
      (when-let [observations (observe current-agent)]
        (log/info (str "OBSERVE: " (:type observations)
                      (when (= (:type observations) :user-input)
                        (str " - " (:content observations)))))
        ;; ORIENT
        (when-let [situation (orient current-agent observations)]
          (log/info (str "ORIENT: ready-for-inference=" (:ready-for-inference situation)))
          ;; DECIDE
          (when-let [decision (decide current-agent situation)]
            (log/info (str "DECIDE: type=" (:type decision)))
            ;; ACT
            (let [result (act current-agent decision)]
              (log/info (str "ACT: continue=" (:continue result) " (iteration " (inc iteration) ")"))
              (when (:continue result)
                ;; Apply memory management to conversation and clear user-prompt after first use
                (let [truncated-conversation (truncate-conversation (:conversation result) (:max-memory current-agent))
                      updated-agent (assoc current-agent
                                          :conversation truncated-conversation
                                          :user-prompt nil)] ; Clear user-prompt after first use
                  (recur updated-agent (inc iteration)))))))))))

(defn start-agent
  "Start the OODA loop agent with default model"
  [{:keys [model system-prompt user-prompt max-iterations max-memory]
    :or {model "mistral-large-latest"
         system-prompt default-system-prompt
         max-iterations 50
         max-memory 10}}]
  (if mistral/TOKEN
    (let [agent (create-agent model system-prompt user-prompt)]
      (run-ooda-loop (assoc agent :max-iterations max-iterations :max-memory max-memory)))
    (log/error "Error: WF_MISTRAL_KEY environment variable not set")))

;; Example usage
(comment
  ;; Start the agent with default model and limits
  (start-agent {:user-prompt "Add a function `addition` in src/clj_sac/core.clj. This function should make an addition with 2 numbers."})
  (start-agent {:user-prompt "Write a simple 'Hello World' program in Python and save it to hello.py"})

  ;; Start the agent with custom limits
  (start-agent {:user-prompt "Complex task requiring many steps"
                :max-iterations 100
                :max-memory 20})

  ;; Start the agent with minimal limits for simple tasks
  (start-agent {:user-prompt "Simple task"
                :max-iterations 10
                :max-memory 5}))
