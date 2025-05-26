(ns clj-sac.llm.mistral
  (:require
   [clojure.string :as string]
   [malle.llm.protocol :as protocol]
   [hato.client :as http]
   [cheshire.core :as json]))

(def TOKEN (System/getenv "WF_MISTRAL_KEY"))

(def CompletionRequest
  [:map
   [:model :string]
   [:messages
    [:sequential [:map
                  [:role :string]
                  [:content :string]]]]])

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

(defrecord Mistral [model-opts]
  protocol/LLM
  (count-tokens [_ messages]
    42)
  (completion [_ messages options]
    (let [{:keys [model temperature max-tokens]
           :or {model "mistral-large-latest"
                temperature 0.7}} (merge model-opts options)
          response (http/post chat-url
                             {:as :json
                              :content-type :json
                              :form-params {:messages messages
                                           :model model
                                           :temperature temperature
                                           :max_tokens max-tokens}
                              :headers {"Authorization" (str "Bearer " TOKEN)}})]
      (-> response :body :choices first :message)))
  (stream-completion [this messages options callback]
    (let [{:keys [model temperature max-tokens]
           :or {model "mistral-large-latest"
                temperature 0.7}} (merge model-opts options)]
      (http/post chat-url
                {:as :stream
                 :content-type :json
                 :form-params {:messages messages
                               :model model
                               :stream true
                               :temperature temperature
                               :max_tokens max-tokens}
                 :headers {"Authorization" (str "Bearer " TOKEN)}
                 :on-body (fn [response chunk]
                            (when-let [data (not-empty (String. chunk))]
                              (doseq [line (filter not-empty (string/split data #"\n"))]
                                (when-not (= line "data: [DONE]")
                                  (let [json-str (string/replace line #"^data: " "")
                                        parsed (json/parse-string json-str true)
                                        content (-> parsed :choices first :delta :content)]
                                    (when content
                                      (callback content)))))))}))))


;(.count-tokens (map->Mistral {:name "Lucian" :title "CEO of Melodrama"}) [])

(defn new [model-opts]
  (map->Mistral model-opts))




(def resp (http/post chat-url
                     {:as :json
                      :async? true
                      :content-type :json
                      :form-params {:messages [{:content "Write me a small haiku"
                                                :role "user"}]
                                    :model "mistral-large-latest"}
                      :headers {"Authorization" (str "Bearer " TOKEN)}}))

;; (:body @resp)
;; (filter (fn [[k _]]
;;           (string/starts-with? k "x-rate"))
;;         (:headers @resp))



;; {:request-time 29162,
;;  :request {:user-info nil, :as :json,
;;            :headers {"Authorization" "Bearer scuhgg1nSBtH2kZxqggLya8ypHK1CqzE", "content-type" "application/json", "accept-encoding" "gzip, deflate"},
;;            :server-port nil, :url "https://api.mistral.ai/v1/chat/completions", :content-type "application/json",
;;            :http-request #object[jdk.internal.net.http.HttpRequestImpl 0x2eaa645b "https://api.mistral.ai/v1/chat/completions POST"],
;;            :uri "/v1/chat/completions", :server-name "api.mistral.ai",
;;            :query-string nil,
;;            :body "{\"model\":\"mistral-large-latest\",\"messages\":[{\"role\":\"user\",\"content\":\"Write me a small haiku\"}]}",
;;            :scheme :https, :request-method :post},
;;  :http-client #object[jdk.internal.net.http.HttpClientFacade 0x208f041a "jdk.internal.net.http.HttpClientImpl@51b4d3f(2)"],
;;  :headers {"content-encoding" "gzip", "x-envoy-upstream-service-time" "28777", "server" "cloudflare", "content-type" "application/json", "access-control-allow-origin" "*", "alt-svc" "h3=\":443\"; ma=86400", "x-kong-upstream-latency" "28778", "x-ratelimitbysize-remaining-minute" "467993", "ratelimitbysize-query-cost" "32007", "ratelimitbysize-limit" "500000", "ratelimitbysize-reset" "40", "x-kong-proxy-latency" "6", "set-cookie" ["__cf_bm=D6tSoiamwhCoL6ghRISrCQ90lyOgbYgFEF0HbAJr8r0-1747139688-1.0.1.1-FNjKjMuM7lvAQ5LolmyppVv.ZUecJUBeRMiP7T2jxgHfcROptDlP5jClzmBsU3lmOCbisdqU3Wi49rJpffrRdqROFw.kx5cFfNqANW1y5lk; path=/; expires=Tue, 13-May-25 13:04:48 GMT; domain=.mistral.ai; HttpOnly; Secure; SameSite=None" "_cfuvid=uDJGxtRvNCjtzldStg5ldEbnUMtCyc.YCqqiIfFSjoM-1747139688834-0.0.1.1-604800000; path=/; domain=.mistral.ai; HttpOnly; Secure; SameSite=None"], "x-ratelimitbysize-limit-month" "1000000000", "cf-cache-status" "DYNAMIC", "cf-ray" "93f2307b1831d2bf-FRA", ":status" "200", "ratelimitbysize-remaining" "467993", "x-ratelimitbysize-limit-minute" "500000", "date" "Tue, 13 May 2025 12:34:48 GMT", "x-kong-request-id" "4e0e4d05e4e8c778ac8e1e4c09b02330", "x-ratelimitbysize-remaining-month" "999967993"},
;;  :status 200,
;;  :content-type :application/json,
;;  :uri "https://api.mistral.ai/v1/chat/completions",
;;  :content-type-params {}, :version :http-2,
;;  :body {:id "7df4991e511440f4a71b636b80e5c760", :object "chat.completion",
;;         :created 1747139660,
;;         :model "mistral-large-latest",
;;         :choices [{:index 0, :message {:role "assistant", :tool_calls nil, :content "Sure, here's a small haiku for you:\n\nWhispers of the breeze,\nDancing leaves in morning light,\nNature's quiet song."}, :finish_reason "stop"}], :usage {:prompt_tokens 9, :total_tokens 47, :completion_tokens 38}}}


#_(defn chat-completion!
    "Documentation: https://api.mistral.ai/docs"
    [messages & {:keys [model]
                 :or {model "mistral-large-latest"}}]
    (http/post chat-url
               {:model model
                :messages messages}
               {:headers {"Authorization" (str "Bearer " TOKEN)}
                :request-schema CompletionRequest
                :response-schema CompletionResponse}))

#_(chat-completion!
   [{:role "user"
     :content "Write me a small haiku"}])

#_(chat-completion!
   [{:role "assistant"
     :content (str "Which them is the most relevant for describing the text bellow?
                  The possible themes are programming, astrology, AI or chemistry.
                  Answer in EDN format that look like {:theme \"one_of_the_theme\", :reason \"why_this_theme\"}.")}
    {:role "user"
     :content "Dark matter is not known to interact with ordinary baryonic matter and radiation except through gravity, making it difficult to detect in the laboratory. The most prevalent explanation is that dark matter is some as-yet-undiscovered subatomic particle, such as either weakly interacting massive particles (WIMPs) or axions.[12] The other main possibility is that dark matter is composed of primordial black holes."}])

;; {:id "80e9f30893bb4d3b88c32f4e67d2aa9a",
;;  :object "chat.completion",
;;  :created 1737796128,
;;  :model "mistral-large-latest",
;;  :choices
;;  [{:index 0,
;;    :message
;;    {:role "assistant",
;;     :tool_calls nil,
;;     :content
;;     "```json\n{\n  \"theme\": \"chemistry\",\n  \"reason\": \"The text discusses subatomic particles, which are a fundamental concept in chemistry, and mentions specific particles like WIMPs and axions.\"\n}\n```"},
;;    :finish_reason "stop"}],
;;  :usage {:prompt_tokens 157, :total_tokens 210, :completion_tokens 53}}


;; {:id "6163bb3581a64525ab2c163dfe1f54cc",
;;  :object "chat.completion",
;;  :created 1737796220,
;;  :model "mistral-large-latest",
;;  :choices
;;  [{:index 0,
;;    :message
;;    {:role "assistant",
;;     :tool_calls nil,
;;     :content
;;     "```edn\n{:theme \"chemistry\"\n :reason \"The text discusses subatomic particles, which are a fundamental concept in chemistry. It also mentions interactions between matter and radiation, which are topics studied in chemical physics.\"}\n```"},
;;    :finish_reason "stop"}],
;;  :usage {:prompt_tokens 158, :total_tokens 211, :completion_tokens 53}}



; Headers example
{"x-envoy-upstream-service-time" "1709",
 "Server" "cloudflare",
 "Content-Type" "application/json",
 "access-control-allow-origin" "*",
 "alt-svc" "h3=\":443\"; ma=86400",
 "x-kong-upstream-latency" "1710",
 "x-ratelimitbysize-remaining-minute" "467993",
 "Connection" "close",
 "ratelimitbysize-query-cost" "32007",
 "ratelimitbysize-limit" "500000",
 "Transfer-Encoding" "chunked",
 "ratelimitbysize-reset" "40",
 "x-kong-proxy-latency" "1",
 "x-ratelimitbysize-limit-month" "1000000000",
 "CF-Cache-Status" "DYNAMIC",
 "CF-RAY" "902cdcf399f2655e-AMS",
 "ratelimitbysize-remaining" "467993",
 "x-ratelimitbysize-limit-minute" "500000",
 "Date" "Thu, 16 Jan 2025 08:51:22 GMT",
 "x-kong-request-id" "bf0ef16c00d01a7280ff1522e343db1f",
 "x-ratelimitbysize-remaining-month" "999871967"}
