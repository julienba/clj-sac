(ns clj-sac.agent
  (:require
   [clj-sac.llm.http.mistral :as mistral]
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.tools.logging :as log]))

;; Tool definitions
(def tools
  [{:type "function"
    :function {:name "read_file"
               :description "Read the contents of a file"
               :parameters {:type "object"
                            :properties {:path {:type "string"
                                                :description "The file path to read"}}
                            :required ["path"]}}}
   {:type "function"
    :function {:name "edit_file"
               :description "Edit a file by replacing old content with new content"
               :parameters {:type "object"
                            :properties {:path {:type "string"
                                                :description "The file path to edit"}
                                         :old_str {:type "string"
                                                   :description "The old content to replace (empty string for new file)"}
                                         :new_str {:type "string"
                                                   :description "The new content to insert"}}
                            :required ["path" "old_str" "new_str"]}}}
   {:type "function"
    :function {:name "list_directory"
               :description "List files and directories in a given path"
               :parameters {:type "object"
                            :properties {:path {:type "string"
                                                :description "The directory path to list"}}
                            :required ["path"]}}}])

;; Tool execution functions
(defn read-file-tool [path]
  (try
    {:success true
     :content (slurp path)}
    (catch Exception e
      {:success false
       :error (.getMessage e)})))

(defn get-current-directory []
  (try
    (.getCanonicalPath (io/file "."))
    (catch Exception e
      (str "Error getting current directory: " (.getMessage e)))))

(get-current-directory)

(defn edit-file-tool [path old-str new-str]
  (try
    (let [file (io/file path)]
      (if (.exists file)
        (if (str/blank? old-str)
          ;; Append to existing file
          (let [current-content (slurp file)
                new-content (str current-content "\n" new-str)]
            (spit path new-content)
            {:success true
             :message (str "Successfully appended to " path)})
          ;; Replace specific content in existing file
          (let [current-content (slurp file)
                new-content (str/replace current-content old-str new-str)]
            (spit path new-content)
            {:success true
             :message (str "Successfully edited " path)}))
        ;; Create new file
        (do
          (io/make-parents file)
          (spit path new-str)
          {:success true
           :message (str "Successfully created " path)})))
    (catch Exception e
      {:success false
       :error (if (instance? clojure.lang.ExceptionInfo e)
                (ex-message e)
                (.getMessage e))})))

(defn list-directory-tool [path]
  (try
    (let [dir (io/file path)]
      (if (.exists dir)
        {:success true
         :files (->> (.listFiles dir)
                     (map #(hash-map :name (.getName %)
                                     :type (if (.isDirectory %) "directory" "file")))
                     (sort-by :name))}
        {:success false
         :error (str "Directory does not exist: " path)}))
    (catch Exception e
      {:success false
       :error (.getMessage e)})))

(defn execute-tool [tool-name args]
  (case tool-name
    "read_file" (read-file-tool (:path args))
    "edit_file" (edit-file-tool (:path args) (:old_str args) (:new_str args))
    "list_directory" (list-directory-tool (:path args))
    {:success false
     :error (str "Unknown tool: " tool-name)}))

;; OODA Loop Implementation
(defprotocol OODALoop
  (observe [this] "Gather information from environment")
  (orient [this observations] "Analyze and synthesize observations")
  (decide [this situation] "Determine the best course of action")
  (act [this decision] "Execute the decision"))

(defn decide-impl [model situation]
  (log/info :decide-impl situation)
  (when (:ready-for-inference situation)
    (try
      (let [response (mistral/chat-completion
                      {:messages (:conversation situation)
                       :model model
                       :tools tools
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

(defrecord Agent [conversation model user-prompt max-iterations max-memory]
  OODALoop

  (observe [_this]
    ;; Observe: Get user input or continue with existing conversation
    (if-let [user-input user-prompt]
      ;; New user input
      (do
        (log/info :line user-input)
        {:type :user-input
         :content user-input
         :timestamp (System/currentTimeMillis)})
      ;; No new input, but continue with conversation if the last message is a tool message
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
    ;; Decide: Get LLM response with potential tool calls
    (decide-impl model situation))

  (act [_this decision]
    ;; Act: Process LLM response and execute any tool calls
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
                                     result (execute-tool tool-name args)]
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
       :continue false})))

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
    (Thread/sleep 500) ; Slow things down in case to prevent a infinit loop to spam the LLM provider

    ;; Check iteration limit
    (when (>= iteration (:max-iterations current-agent))
      (log/warn (str "Reached maximum iterations (" (:max-iterations current-agent) "). Stopping."))
      (reduced nil))

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
              ;; Apply memory management to conversation
              (let [truncated-conversation (truncate-conversation (:conversation result) (:max-memory current-agent))
                    updated-agent (assoc current-agent :conversation truncated-conversation)]
                (recur updated-agent (inc iteration))))))))))

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
  ;(set-user-prompt "Write me an haiku")
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
