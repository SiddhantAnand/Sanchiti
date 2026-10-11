package com.sid.expense.ai;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
public class GeminiClient implements AiClient {

  private final AiSettings settings;
  private final JsonMapper json;
  private final RestClient http;

  public GeminiClient(AiSettings settings, JsonMapper json) {
    this.settings = settings;
    this.json = json;
    var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    factory.setReadTimeout(Duration.ofSeconds(30));
    this.http = RestClient.builder()
        .baseUrl("https://generativelanguage.googleapis.com/v1beta")
        .requestFactory(factory)
        .build();
  }

  @Override
  public boolean isEnabled() {
    return settings.current().enabled();
  }

  /** Throws unless Google accepts this key for this model; used before an admin's key is saved. */
  public void verify(String apiKey, String model) {
    try {
      http.get().uri("/models/{model}", model).header("x-goog-api-key", apiKey).retrieve().toBodilessEntity();
    } catch (RestClientResponseException e) {
      throw new AiException(e.getStatusCode().value() == 404
          ? "Unknown model " + model
          : "Gemini rejected this key (" + e.getStatusCode().value() + ")");
    } catch (RestClientException e) {
      throw new AiException("Could not reach Gemini: " + e.getMessage());
    }
  }

  @Override
  public AiResult generate(String system, String prompt, Map<String, Object> schema) {
    AiSettings.Current ai = settings.current();
    if (!ai.enabled()) throw new AiException("No Gemini API key is set");
    String apiKey = ai.apiKey();
    String model = ai.model();

    String body = json.writeValueAsString(Map.of(
        "systemInstruction", Map.of("parts", List.of(Map.of("text", system))),
        "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", prompt)))),
        "generationConfig", Map.of(
            "responseMimeType", "application/json",
            "responseSchema", schema,
            "temperature", 0.2)));

    String raw;
    try {
      raw = http.post()
          .uri("/models/{model}:generateContent", model)
          .header("x-goog-api-key", apiKey)
          .contentType(MediaType.APPLICATION_JSON)
          .body(body)
          .retrieve()
          .body(String.class);
    } catch (RestClientResponseException e) {
      String detail = e.getResponseBodyAsString();
      throw new AiException("Gemini returned " + e.getStatusCode().value() + ": "
          + detail.substring(0, Math.min(300, detail.length())));
    } catch (RestClientException e) {
      throw new AiException("Gemini request failed: " + e.getMessage());
    }

    try {
      Map<?, ?> response = json.readValue(raw, Map.class);
      String text = firstCandidateText(response);
      if (text == null) throw new AiException("Gemini returned no content");
      return new AiResult(json.readValue(text, Object.class), model, response.get("usageMetadata"));
    } catch (JacksonException e) {
      throw new AiException("Gemini returned invalid JSON");
    }
  }

  private static String firstCandidateText(Map<?, ?> response) {
    if (response.get("candidates") instanceof List<?> candidates
        && !candidates.isEmpty()
        && candidates.getFirst() instanceof Map<?, ?> candidate
        && candidate.get("content") instanceof Map<?, ?> content
        && content.get("parts") instanceof List<?> parts
        && !parts.isEmpty()
        && parts.getFirst() instanceof Map<?, ?> part
        && part.get("text") instanceof String text) {
      return text;
    }
    return null;
  }
}
