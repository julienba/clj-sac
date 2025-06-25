(ns clj-sac.agent2
  (:require
   [clj-sac.llm.http.mistral :as mistral]
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]))

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
      (if (and (.exists file) (not (str/blank? old-str)))
        ;; Replace content in existing file
        (let [current-content (slurp file)
              new-content (str/replace current-content old-str new-str)]
          (spit path new-content)
          {:success true
           :message (str "Successfully edited " path)})
        ;; Create new file or replace entirely if old-str is empty
        (do
          (io/make-parents file)
          (spit path new-str)
          {:success true
           :message (str "Successfully created/wrote " path)})))
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

(def user-prompt (atom nil))

(defn set-user-prompt [prompt]
  (reset! user-prompt prompt))

(defn decide-impl [model situation]
  (println :decide-impl situation)
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


;; (mistral/chat-completion
;;  {:messages [{:role "system",
;;               :content
;;               "You are a helpful coding assistant with access to file operations.\n                               When you need to read files, edit files, or list directories, use the available tools.\n                               Always explain what you're doing before using tools.\n                               For file edits, be precise with the old_str parameter - it must match exactly what's in the file."}
;;              {:role "user", :content "write me an haiku"}]
;;   :model "mistral-large-latest"
;;   :tools []
;;   :temperature 0.7}
;;  {:headers {"Authorization" (str "Bearer " mistral/TOKEN)}})

(defrecord Agent [conversation model]
  OODALoop

  (observe [_this]
    ;; Observe: Get user input or continue with existing conversation
    (if-let [user-input @user-prompt]
      ;; New user input
      (do
        (prn :line user-input)
        (reset! user-prompt nil)
        {:type :user-input
         :content user-input
         :timestamp (System/currentTimeMillis)})
      ;; No new input, but continue with conversation if we have tool results
      (when (and (seq conversation)
                 (some #(= (:role %) "tool") conversation))
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
                  (println (str "\u001b[92mTool executed successfully\u001b[0m: "
                                (or (:message result) "Operation completed")))
                  (println (str "\u001b[91mTool error\u001b[0m: " (:error result))))))

            ;; Return updated state for next iteration
            {:conversation final-conversation
             :continue true})

          ;; No tool calls - just add assistant response and print it
          (do
            (println (str "\u001b[93mClaude\u001b[0m: " (:content assistant-message)))
            {:conversation (conj (:conversation decision) assistant-message)
             :continue true})))

      :error
      (do
        (println (str "\u001b[91mError\u001b[0m: " (:error decision)))
        {:conversation (:conversation decision)
         :continue true})

      ;; Default case
      {:conversation conversation
       :continue false})))

(defn create-agent
  "Create a new OODA loop agent with initial system message"
  [model]
  (let [system-message {:role "system"
                        :content "You are a helpful coding assistant with access to file operations.
                               When you need to read files, edit files, or list directories, use the available tools.
                               Always explain what you're doing before using tools.
                               For file edits, be precise with the old_str parameter - it must match exactly what's in the file."}]
    (->Agent [system-message] model)))

(defn run-ooda-loop
  "Main OODA loop execution"
  [agent]
  (println "OODA Loop Agent started (Ctrl+C to quit)")
  (println "Using Mistral model for decisions")
  (loop [current-agent agent]
    (tap> [:loop current-agent])
    (Thread/sleep 2000)
    ;; OBSERVE
    (when-let [observations (observe current-agent)]
      (tap> [:observations observations])
      (println (str "\u001b[94mOBSERVE\u001b[0m: " (:type observations)
                    (when (= (:type observations) :user-input)
                      (str " - " (:content observations)))))
      ;; ORIENT
      (when-let [situation (orient current-agent observations)]
        (tap> [:orient situation])
        (println (str "\u001b[94mORIENT\u001b[0m: ready-for-inference=" (:ready-for-inference situation)))
        ;; DECIDE
        (when-let [decision (decide current-agent situation)]
          (tap> [:decide decision])
          (println (str "\u001b[94mDECIDE\u001b[0m: type=" (:type decision)))
          ;; ACT
          (let [result (act current-agent decision)]
            (tap> [:act result])
            (println (str "\u001b[94mACT\u001b[0m: continue=" (:continue result)))
            (when (:continue result)
              (tap> [:result (:conversation result)])
              (recur (assoc current-agent :conversation (:conversation result))))))))))

(defn start-agent
  "Start the OODA loop agent with default model"
  ([]
   (start-agent "mistral-large-latest"))
  ([model]
   (if mistral/TOKEN
     (let [agent (create-agent model)]
       (run-ooda-loop agent))
     (println "Error: WF_MISTRAL_KEY environment variable not set"))))

;; Example usage
(comment
  ;; Start the agent with default model
  ;(set-user-prompt "Write me an haiku")
  (set-user-prompt "Add a function addition in src/clj_sac/core.clj. This function should make an addition with 2 numbers.")
  (start-agent)

  ;; Or with specific model
  (start-agent "mistral-medium-latest"))

;; :conversation [{:role "system", :content "You are a helpful coding assistant with access to file operations.\n                               When you need to read files, edit files, or list directories, use the available tools.\n                               Always explain what you're doing before using tools.\n                               For file edits, be precise with the old_str parameter - it must match exactly what's in the file."}
;;                {:role "user", :content "Add a function addition in src/clj_sac/core.clj. This function should make an addition with 2 numbers."}
;;                {:role "assistant", :tool_calls [{:id "M700cpBG5", :function {:name "read_file", :arguments "{\"path\": \"src/clj_sac/core.clj\"}"}, :index 0}], :content "To add a function for addition in the `src/clj_sac/core.clj` file, I will first read the file to understand its current structure and then edit the file to add the new function. Here are the steps I will follow:\n\n1. **Read the File**: I will read the contents of `src/clj_sac/core.clj` to ensure that I add the function in the correct location without disrupting the existing code.\n2. **Edit the File**: I will then edit the file to add the new addition function. The function will take two numbers as arguments and return their sum.\n\nLet's proceed with these steps."}
;;                {:role "tool", :tool_call_id "M700cpBG5", :content "{\"success\":true,\"content\":\"(ns clj-sac.core\\n  (:require\\n   [malle.llm.protocol :as p]\\n   [malle.llm.mistral :as llm.mistral]))\\n\\n\\n;(p\\/count-tokens (llm.mistral\\/new {}) [])\"}"}],

;