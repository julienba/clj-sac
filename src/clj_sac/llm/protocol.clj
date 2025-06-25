(ns clj-sac.llm.protocol)

(defprotocol LLM
  "Protocol for interacting with LLM providers"
  (count-tokens [this messages]
    "Count the number of tokens in the messages.
     Returns nil if not supported")

  (completion [this messages options]
    "Send a completion request to the LLM and return the response.
     - messages: A sequence of message maps with :role and :content
     - options: A map of options conforming to LLMOptionsSchema
     Returns a map conforming to ResponseSchema"))
