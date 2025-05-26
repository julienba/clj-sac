(ns clj-sac.llm.protocol
  (:require
   [malli.core :as m]))

(def MessageSchema
  "Schema for a message in a conversation with an LLM"
  [:map
   [:role {:doc "The role of the message sender (user, assistant, system, etc.)"}
    :string]
   [:content {:doc "The content of the message"}
    :string]])

(def ToolSchema
  "Schema for a tool that can be used by the LLM"
  [:map
   [:type {:doc "The type of the tool"}
    :string]
   [:function [:map
               [:name {:doc "The name of the function"} :string]
               [:description {:doc "Description of what the function does"} :string]
               [:parameters {:doc "JSON Schema object describing the parameters"} :map]]]])

(def LLMOptionsSchema
  "Common options for LLM calls"
  [:map
   [:model {:doc "The model to use" :optional true} :string]
   [:temperature {:doc "Controls randomness (0-1)" :optional true} :double]
   [:max_tokens {:doc "Maximum tokens to generate" :optional true} :int]
   [:tools {:doc "List of tools available to the model" :optional true}
    [:sequential ToolSchema]]
   [:system {:doc "System message to guide model behavior" :optional true} :string]
   [:schema {:doc "Schema for validating/structuring the output" :optional true} :any]])

(def TokenUsageSchema
  "Schema for token usage metrics"
  [:map
   [:prompt_tokens {:doc "Number of tokens in the prompt"} :int]
   [:completion_tokens {:doc "Number of tokens in the completion"} :int]
   [:total_tokens {:doc "Total tokens used"} :int]])

(def ResponseSchema
  "Schema for LLM response"
  [:map
   [:content {:doc "The generated content"} :string]
   [:usage {:doc "Token usage information" :optional true} TokenUsageSchema]
   [:finish_reason {:doc "Reason why generation stopped" :optional true} :string]
   [:raw {:doc "Raw response from the LLM provider" :optional true} :any]])

(defprotocol LLM
  "Protocol for interacting with LLM providers"
  (count-tokens [this messages]
    "Count the number of tokens in the messages.
     Returns nil if not supported")

  (completion [this messages options]
    "Send a completion request to the LLM and return the response.
     - messages: A sequence of message maps with :role and :content
     - options: A map of options conforming to LLMOptionsSchema
     Returns a map conforming to ResponseSchema")

  (stream-completion [this messages options callback]
    "Stream a completion request to the LLM.
     - messages: A sequence of message maps with :role and :content
     - options: A map of options conforming to LLMOptionsSchema
     - callback: A function that takes a map with :content (partial response)
     Returns a promise that resolves with a map conforming to ResponseSchema"))
