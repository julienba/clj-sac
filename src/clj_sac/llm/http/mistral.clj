(ns clj-sac.llm.http.mistral
  "Official documentation: https://docs.mistral.ai/api/
   Example of function calling: https://docs.mistral.ai/capabilities/function_calling/"
  (:require [cheshire.core :as json]
            [clj-sac.http :as http]
            [clojure.string :as string]
            [clojure.core.async :as a]))

(def TOKEN (System/getenv "WF_MISTRAL_KEY"))

(def ResponseHeaders
  [:map
   [:ratelimitbysize-limit pos-int?]
   [:ratelimitbysize-query-cost pos-int?]
   [:ratelimitbysize-reset pos-int?]
   [:x-kong-upstream-latency pos-int?]
   [:x-ratelimitbysize-remaining-minute pos-int?]
   [:x-ratelimitbysize-remaining-month pos-int?]])

(def CompletionRequest
  [:map
   ;; Required parameters
   [:model :string]
   [:messages
    [:sequential [:map
                  [:role [:enum "system" "user" "assistant" "tool"]]
                  [:content :string]
                  [:tool_call_id {:optional true} :string]
                  [:tool_calls {:optional true} [:maybe [:sequential
                                                         [:map
                                                          [:id :string]
                                                          [:type {:optional true} [:enum "function"]]
                                                          [:index :int]
                                                          [:function
                                                           [:map
                                                            [:name :string]
                                                            [:arguments :string]]]]]]]]]]

   ;; Optional parameters sorted alphabetically
   [:frequency_penalty {:optional true} [:double {:min -2.0 :max 2.0}]]
   [:max_tokens {:optional true} :int]
   [:n {:optional true} :int]
   [:parallel_tool_calls {:optional true} :boolean]
   [:presence_penalty {:optional true} [:double {:min -2.0 :max 2.0}]]
   [:prediction {:optional true} [:map
                                  [:type [:enum "content"]]
                                  [:content :string]]]
   [:random_seed {:optional true} :int]
   [:response_format {:optional true} [:map
                                       [:type [:enum "text" "json"]]
                                       [:json_schema {:optional true} :any]]]
   [:safe_prompt {:optional true} :boolean]
   [:stop {:optional true} [:or :string [:sequential :string]]]
   [:stream {:optional true} :boolean]
   [:temperature {:optional true} [:double {:min 0.0 :max 1.0}]]
   [:tool_choice {:optional true} [:or
                                   [:enum "none" "auto"]
                                   [:map
                                    [:type [:enum "function"]]
                                    [:function
                                     [:map
                                      [:name :string]]]]]]
   [:tools {:optional true} [:sequential
                             [:map
                              [:type [:enum "function"]]
                              [:function
                               [:map
                                [:name :string]
                                [:description {:optional true} :string]
                                [:parameters :any]]]]]]
   [:top_p {:optional true} [:double {:min 0.0 :max 1.0}]]])

(def CompletionResponse
  [:map
   [:id :string]
   [:object :string]
   [:created :int]
   [:model :string]
   [:choices [:sequential
              [:map
               [:index :int]
               [:message [:map
                          [:role :string]
                          [:tool_calls [:maybe :any]]
                          [:content :string]]]
               [:finish_reason :string]]]]
   [:usage [:map
            [:prompt_tokens :int]
            [:total_tokens :int]
            [:completion_tokens :int]]]])

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
    (http/post chat-url form-params (merge {:parse-json? true
                                            :schemas {:request-schema CompletionRequest
                                                      ;:response-header-schema ResponseHeaders
                                                      :response-schema CompletionResponse}}
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
        sse-channel (http/stream-post chat-url
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

