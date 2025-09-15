(ns clj-sac.llm.http.gemini-schema)

(def CompletionResponse
  "Malli schema for Gemini API completion response"
  [:map
   [:candidates [:sequential
                 [:map
                  [:content [:map
                             [:parts [:sequential
                                      [:map
                                       [:text :string]]]]
                             [:role [:enum "user" "model"]]]]
                  [:finishReason [:enum "STOP" "MAX_TOKENS" "SAFETY" "RECITATION" "OTHER"]]
                  [:safetyRatings {:optional true} [:sequential
                                                    [:map
                                                     [:category [:enum
                                                                 "HARM_CATEGORY_HARASSMENT"
                                                                 "HARM_CATEGORY_HATE_SPEECH"
                                                                 "HARM_CATEGORY_SEXUALLY_EXPLICIT"
                                                                 "HARM_CATEGORY_DANGEROUS_CONTENT"]]
                                                     [:probability [:enum "NEGLIGIBLE" "LOW" "MEDIUM" "HIGH"]]]]]
                  [:citationMetadata {:optional true} [:map
                                                       [:citationSources [:sequential
                                                                          [:map
                                                                           [:startIndex :int]
                                                                           [:endIndex :int]
                                                                           [:uri :string]
                                                                           [:license :string]]]]]]
                  [:tokenCount {:optional true} :int]
                  [:groundingMetadata {:optional true} [:map
                                                        [:webSearchQueriesUsed [:sequential :string]]
                                                        [:groundingChunks [:sequential
                                                                           [:map
                                                                            [:web {:optional true} [:map
                                                                                                    [:uri :string]
                                                                                                    [:title :string]]]]]]]]
                  [:avgLogprobs {:optional true} :double]]]]
   [:usageMetadata [:map
                    [:promptTokenCount :int]
                    [:candidatesTokenCount :int]
                    [:totalTokenCount :int]
                    [:promptTokensDetails {:optional true} [:sequential
                                                            [:map
                                                             [:modality [:enum "TEXT" "IMAGE" "AUDIO" "VIDEO"]]
                                                             [:tokenCount :int]]]]
                    [:candidatesTokensDetails {:optional true} [:sequential
                                                                [:map
                                                                 [:modality [:enum "TEXT" "IMAGE" "AUDIO" "VIDEO"]]
                                                                 [:tokenCount :int]]]]]]
   [:modelVersion {:optional true} :string]
   [:responseId {:optional true} :string]])

