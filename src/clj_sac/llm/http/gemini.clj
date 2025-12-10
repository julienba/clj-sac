(ns clj-sac.llm.http.gemini
  "Official documentation: https://ai.google.dev/gemini-api/docs"
  (:require [clj-sac.http :as http]
            [clj-sac.llm.http.gemini-schema :as schema]))

(defn- get-chat-url [model]
  (format "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent" model))

(defn chat-completion
  "Generate content using Google's Gemini API.

   Parameters:
   - model-opts: Map containing:
     - :messages - Vector of message maps with :role and :content
     - :model - Model name (e.g., 'gemini-1.5-flash', 'gemini-1.5-pro')
     - :max-tokens - Maximum tokens to generate (optional)
     - :temperature - Temperature for generation (optional, default 0.7)
     - :tools - Function calling tools (optional)
     - :tool-choice - Tool choice strategy (optional)
   - http-opts: HTTP client options, you need to pass {:headers {\"x-goog-api-key\" TOKEN}} at minima

   Returns the API response as a map."
  [{:keys [messages max-tokens model temperature] :as _model-opts
    :or {temperature 0.7}}
   http-opts]
  (assert (and messages model) "Both messages and model are required")
  (let [url (get-chat-url model)
        ;; Transform messages to Gemini format
        contents (mapv (fn [msg]
                         {:role (case (:role msg)
                                  "user" "user"
                                  "assistant" "model"
                                  "system" "user" ; Gemini doesn't have system role, treat as user
                                  "user")
                          :parts [{:text (:content msg)}]})
                       messages)
        request-body (cond-> {:contents contents
                              :generationConfig {:temperature temperature}}
                       max-tokens (assoc-in [:generationConfig :maxOutputTokens] max-tokens))]
    (http/POST url
               request-body
               (merge {:parse-json? true
                       :schemas {:response-schema schema/CompletionResponse}}
                      http-opts))))

