(ns clj-sac.agent
  "Observe, Orient, Decide, Act loop"
  (:require
   [clj-sac.llm.http.mistral :refer [chat-completion]]
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.java.shell]))

;; =====================================
;; SYSTEM PROMPT
;; =====================================

(def system-prompt
  "You are a helpful coding assistant with access to file operations.
   When you need to read files, edit files, or list directories, use the available tools.
   Always explain what you're doing before using tools.")

;; =====================================
;; STATE MANAGEMENT
;; =====================================

(defn create-agent-state
  "Creates initial agent state with goal, memory, and metadata"
  [goal & {:keys [max-iterations max-memory-size]
           :or {max-iterations 50 max-memory-size 100}}]
  {:goal goal
   :memory []
   :iteration 0
   :status :active
   :max-iterations max-iterations
   :max-memory-size max-memory-size
   :created-at (java.time.Instant/now)})

(defn add-to-memory
  "Adds an entry to agent memory, pruning if necessary"
  [state entry]
  (let [new-memory (conj (:memory state) entry)
        pruned-memory (if (> (count new-memory) (:max-memory-size state))
                        (vec (take-last (:max-memory-size state) new-memory))
                        new-memory)]
    (assoc state :memory pruned-memory)))

;; =====================================
;; TOOL BELT
;; =====================================

(defn web-search-tool
  "Simulated web search - replace with real implementation"
  [query]
  {:tool :web-search
   :input query
   :output (str "Search results for: " query " (This is a simulated result)")
   :success true})

(defn read-file-tool
  "Read file from filesystem"
  [filepath]
  (try
    {:tool :read-file
     :input filepath
     :output (slurp filepath)
     :success true}
    (catch Exception e
      {:tool :read-file
       :input filepath
       :output (str "Error reading file: " (.getMessage e))
       :success false})))

(defn write-file-tool
  "Write content to file"
  [filepath content]
  (try
    (spit filepath content)
    {:tool :write-file
     :input {:filepath filepath :content content}
     :output (str "Successfully wrote to " filepath)
     :success true}
    (catch Exception e
      {:tool :write-file
       :input {:filepath filepath :content content}
       :output (str "Error writing file: " (.getMessage e))
       :success false})))

(defn shell-command-tool
  "Execute shell command"
  [command]
  (try
    (let [result (clojure.java.shell/sh "bash" "-c" command)]
      {:tool :shell-command
       :input command
       :output {:stdout (:out result)
                :stderr (:err result)
                :exit-code (:exit result)}
       :success (zero? (:exit result))})
    (catch Exception e
      {:tool :shell-command
       :input command
       :output (str "Error executing command: " (.getMessage e))
       :success false})))

(defn think-tool
  "Allow agent to record its thoughts without taking action"
  [thoughts]
  {:tool :think
   :input thoughts
   :output (str "Recorded thoughts: " thoughts)
   :success true})

(defn finish-tool
  "Signal that the agent has completed its goal"
  [summary]
  {:tool :finish
   :input summary
   :output (str "Task completed: " summary)
   :success true})

(def default-tool-belt
  "Default set of tools available to the agent"
  {:web-search {:fn web-search-tool
                :description "Search the web for information about a topic"}
   :read-file {:fn read-file-tool
               :description "Read the contents of a file from the filesystem"}
   :write-file {:fn write-file-tool
                :description "Write content to a file on the filesystem"}
   :shell-command {:fn shell-command-tool
                   :description "Execute a shell command and return the result"}
   :think {:fn think-tool
           :description "Record thoughts or reasoning without taking external action"}
   :finish {:fn finish-tool
            :description "Signal that the primary goal has been completed"}})

;; =====================================
;; THE MIND (LLM PROMPTING)
;; =====================================

(defn format-memory
  "Format agent memory for inclusion in prompt"
  [memory]
  (if (empty? memory)
    "No previous actions taken."
    (str/join "\n\n"
              (map-indexed
                (fn [idx entry]
                  (str "Action " (inc idx) ":\n"
                       "  Tool: " (:tool entry) "\n"
                       "  Input: " (pr-str (:input entry)) "\n"
                       "  Output: " (pr-str (:output entry)) "\n"
                       "  Success: " (:success entry)))
                memory))))

(defn format-tools
  "Format available tools for inclusion in prompt"
  [tool-belt]
  (str/join "\n"
            (map (fn [[tool-name tool-spec]]
                   (str "- " (name tool-name) ": " (:description tool-spec)))
                 tool-belt)))

(defn construct-prompt
  "Construct the reasoning prompt for the LLM"
  [state tool-belt]
  (str system-prompt "\n\n"
       "You are an autonomous agent with the following goal:\n"
       "GOAL: " (:goal state) "\n\n"

       "CURRENT STATUS:\n"
       "- Iteration: " (:iteration state) "/" (:max-iterations state) "\n"
       "- Status: " (:status state) "\n\n"

       "MEMORY (Previous Actions):\n"
       (format-memory (:memory state)) "\n\n"

       "AVAILABLE TOOLS:\n"
       (format-tools tool-belt) "\n\n"

       "TOOL INPUT FORMATS:\n"
       "- write-file: Use a JSON object with 'filename' and 'content' fields\n"
       "- read-file: Use a string with the file path\n"
       "- shell-command: Use a string with the command to execute\n"
       "- think: Use a string with your thoughts\n"
       "- finish: Use a string with a summary of completion\n"
       "- web-search: Use a string with the search query\n\n"

       "INSTRUCTIONS:\n"
       "1. Analyze the current situation based on your goal and memory\n"
       "2. Choose the most appropriate tool to use next\n"
       "3. Provide the input for that tool in the correct format\n"
       "4. Explain your reasoning\n\n"

       "Respond with a JSON object in this exact format:\n"
       "{\n"
       "  \"reasoning\": \"Your step-by-step reasoning process\",\n"
       "  \"tool\": \"tool_name\",\n"
       "  \"input\": \"input_for_the_tool\"\n"
       "}\n\n"

       "If you believe the goal has been achieved, use the 'finish' tool.\n"
       "If you need to think through the problem, use the 'think' tool.\n"
       "Be concise but thorough in your reasoning."))

(defn parse-llm-response
  "Parse the LLM's JSON response into a structured decision"
  [response-content]
  (try
    (let [parsed (json/parse-string response-content true)]
      (if (and (:tool parsed) (:input parsed))
        {:success true :decision parsed}
        {:success false :error "Missing required fields: tool and input"}))
    (catch Exception e
      {:success false :error (str "Failed to parse JSON response: " (.getMessage e))})))

(defn get-llm-decision
  "Get the agent's next decision from the LLM"
  [llm state tool-belt]
  (let [prompt (construct-prompt state tool-belt)
        messages [{:role "system" :content system-prompt}
                  {:role "user" :content prompt}]
        response (chat-completion {:messages messages
                                   :model (:model llm "mistral-large-latest")
                                   :temperature 0.3
                                   :max-tokens 500}
                                  {:headers {"Authorization" (str "Bearer " (System/getenv "WF_MISTRAL_KEY"))}})]
    (parse-llm-response (-> response :body :choices first :message :content))))

;; =====================================
;; THE OODA LOOP
;; =====================================

(defn observe
  "OBSERVE: Read current state from atom"
  [agent-atom]
  @agent-atom)

(defn orient
  "ORIENT: Construct prompt and get LLM decision"
  [llm state tool-belt]
  (get-llm-decision llm state tool-belt))

(defn decide
  "DECIDE: Validate and prepare the chosen action"
  [decision tool-belt]
  (let [tool-name (keyword (:tool decision))
        tool-spec (get tool-belt tool-name)]
    (if tool-spec
      {:success true :tool-name tool-name :tool-fn (:fn tool-spec) :input (:input decision)}
      {:success false :error (str "Unknown tool: " (:tool decision))})))

(defn act
  "ACT: Execute the chosen tool and return the result"
  [tool-fn input]
  (try
    (cond
      ;; Handle write-file tool specifically
      (= tool-fn write-file-tool)
      (if (map? input)
        (write-file-tool (:filename input) (:content input))
        (write-file-tool input "default content"))

      ;; Handle other tools normally
      :else
      (tool-fn input))
    (catch Exception e
      {:tool :error
       :input input
       :output (str "Error executing tool: " (.getMessage e))
       :success false})))

(defn should-continue?
  "Check if the agent should continue running"
  [state result]
  (and (= (:status state) :active)
       (< (:iteration state) (:max-iterations state))
       (not= (:tool result) :finish)))

(defn update-state
  "Update agent state after an action"
  [state _result]
  (let [new-state (-> state
                      (add-to-memory _result)
                      (update :iteration inc))]
    (cond
      (= (:tool _result) :finish)
      (assoc new-state :status :completed)

      (>= (:iteration new-state) (:max-iterations new-state))
      (assoc new-state :status :max-iterations-reached)

      (not (:success _result))
      new-state ; Continue even on errors, but record them

      :else
      new-state)))

(defn ooda-loop
  "Main OODA loop - recursive function implementing the agent's behavior"
  [llm agent-atom tool-belt & {:keys [verbose?] :or {verbose? false}}]
  (let [;; OBSERVE
        state (observe agent-atom)

        _ (when verbose?
            (println (str "\n=== ITERATION " (:iteration state) " ===")))

        ;; Check if we should continue
        continue-loop? (and (= (:status state) :active)
                           (< (:iteration state) (:max-iterations state)))]

    (if-not continue-loop?
      (do
        (when verbose?
          (println "Agent stopping. Status:" (:status state)))
        state)

      (let [;; ORIENT
            decision-result (orient llm state tool-belt)

            _ (when verbose?
                (println "Decision:" decision-result))

            ;; DECIDE
            action-result (if (:success decision-result)
                           (decide (:decision decision-result) tool-belt)
                           {:success false :error (:error decision-result)})]

        (if-not (:success action-result)
          (do
            (when verbose?
              (println "Decision failed:" (:error action-result)))
            ;; Update state with error and continue
            (let [error-result {:tool :error
                                :input (:decision decision-result)
                                :output (:error action-result)
                                :success false}
                  new-state (update-state state error-result)]
              (swap! agent-atom (constantly new-state))
              (recur llm agent-atom tool-belt {:verbose? verbose?})))

          (let [;; ACT
                result (act (:tool-fn action-result) (:input action-result))

                _ (when verbose?
                    (println "Action result:" result))

                ;; Update state
                new-state (update-state state result)]

            ;; Update the atom
            (swap! agent-atom (constantly new-state))

            ;; Continue the loop if appropriate
            (if (should-continue? new-state result)
              (recur llm agent-atom tool-belt {:verbose? verbose?})
              new-state)))))))

;; =====================================
;; PUBLIC API
;; =====================================

(defn create-agent
  "Create a new autonomous agent"
  [goal & {:keys [tool-belt max-iterations max-memory-size]
           :or {tool-belt default-tool-belt
                max-iterations 1
                max-memory-size 1}}]
  (atom (create-agent-state goal
                           :max-iterations max-iterations
                           :max-memory-size max-memory-size)))

(defn run-agent
  "Run the autonomous agent to completion"
  [llm agent-atom & {:keys [tool-belt verbose?]
                     :or {tool-belt default-tool-belt
                          verbose? false}}]
  (let [final-tool-belt tool-belt]
    (ooda-loop llm agent-atom final-tool-belt :verbose? verbose?)))

(defn agent-status
  "Get current status of the agent"
  [agent-atom]
  (select-keys @agent-atom [:goal :status :iteration :max-iterations]))

(defn agent-memory
  "Get the agent's memory"
  [agent-atom]
  (:memory @agent-atom))

;; =====================================
;; EXAMPLE USAGE
;; =====================================

(comment
  ;; Create an LLM instance
  (def my-llm {:model "mistral-large-latest" :temperature 0.7})

  ;; Create an agent with a goal
  ;(def my-agent (create-agent "Write a simple 'Hello World' program in Python and save it to hello.py"))
  (def my-agent (create-agent "Add a function addition in src/clj_sac/core.clj. This function should make an addition with 2 numbers."))

  ;; Run the agent
  (run-agent my-llm my-agent :verbose? true)

  ;; Check status
  (agent-status my-agent)

  ;; View memory
  (agent-memory my-agent)
  )