(ns clj-sac.llm.http.mistral
  "Official documentation: https://docs.mistral.ai/api/
   Example of function calling: https://docs.mistral.ai/capabilities/function_calling/"
  (:require [cheshire.core :as json]
            [clj-sac.http :as http]
            [clj-sac.llm.http.mistral-schema :as schema]
            [clojure.string :as string]
            [clojure.core.async :as a]))

(def TOKEN (System/getenv "WF_MISTRAL_KEY"))

(def chat-url "https://api.mistral.ai/v1/chat/completions")

(defn chat-completion
  [{:keys [messages max-tokens model temperature tools tool-choice] :as _model-opts
    :or {temperature 0.7}}
   http-opts]
  (assert (and messages model))
  (let [form-params (cond-> {:messages messages
                             :model model
                             :temperature temperature}
                      max-tokens (assoc :max_tokens max-tokens)
                      tools (assoc :tools tools)
                      tool-choice (assoc :tool_choice tool-choice))]
    (http/POST chat-url form-params (merge {:schemas {:request-schema schema/CompletionRequest
                                                      ;:response-header-schema ResponseHeaders
                                                      :response-schema schema/CompletionResponse}}
                                           http-opts))))

(defn stream-chat-completion
  "Stream chat completion using Server-Sent Events.
   Returns a core.async channel that will receive streaming response chunks.

   Each chunk will be a map with the following structure:
   - {:content \"text\"} for text content
   - {:tool-call tool-call-data} for tool calls
   - :done when the stream is complete
   - {:error exception} if an error occurs"
  [{:keys [messages max-tokens model temperature tools tool-choice] :as _model-opts
    :or {temperature 0.7}}
   http-opts]
  (assert (and messages model))
  (let [form-params (cond-> {:messages messages
                             :model model
                             :temperature temperature
                             :stream true}
                      max-tokens (assoc :max_tokens max-tokens)
                      tools (assoc :tools tools)
                      tool-choice (assoc :tool_choice tool-choice))

        ;; Create the SSE stream
        sse-channel (http/stream-POST chat-url
                                      (json/generate-string form-params)
                                      (merge {:headers {"Content-Type" "application/json"}
                                              :parse-event (fn [raw-event]
                                                            (let [data-idx (string/index-of raw-event "{")
                                                                  done-idx (string/index-of raw-event "[DONE]")]
                                                              (if done-idx
                                                                :done
                                                                (when data-idx
                                                                  (try
                                                                    (-> (subs raw-event data-idx)
                                                                        (json/parse-string true))
                                                                    (catch Exception _
                                                                      nil))))))}
                                             http-opts))

        ;; Create output channel for processed events
        output-channel (a/chan)]

    ;; Process SSE events and transform them into a more usable format
    (a/thread
      (try
        (loop []
          (when-let [event (a/<!! sse-channel)]
            (if (= event :done)
              (do
                (a/>!! output-channel :done)
                (a/close! output-channel))
              (when (map? event)
                (let [choices (:choices event)
                      choice (first choices)
                      delta (:delta choice)]
                  (cond
                    ;; Tool call
                    (:tool_calls delta)
                    (doseq [tool-call (:tool_calls delta)]
                      (a/>!! output-channel {:tool-call tool-call}))

                    ;; Text content
                    (:content delta)
                    (a/>!! output-channel {:content (:content delta)})

                    ;; Finish reason
                    (:finish_reason choice)
                    (a/>!! output-channel {:finish-reason (:finish_reason choice)}))
                  (recur))))))
        (catch Exception e
          (a/>!! output-channel {:error e})
          (a/close! output-channel))))

    output-channel))

