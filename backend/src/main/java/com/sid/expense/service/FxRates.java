package com.sid.expense.service;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Daily reference rates to INR (European Central Bank, via Frankfurter); weekends use the last working day. */
@Component
public class FxRates {

  public record Rate(BigDecimal perUnit, LocalDate date) {}

  private static final Logger log = LoggerFactory.getLogger(FxRates.class);

  private final RestClient http;
  private final Map<String, Rate> cache = new ConcurrentHashMap<>();

  public FxRates(@Value("${fx.url:https://api.frankfurter.dev/v1}") String url) {
    var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    factory.setReadTimeout(Duration.ofSeconds(8));
    this.http = RestClient.builder().baseUrl(url).requestFactory(factory).build();
  }

  /** Empty when the currency isn't covered or the service can't be reached. */
  public Optional<Rate> toInr(String currency, LocalDate date) {
    String key = currency + "|" + date;
    Rate cached = cache.get(key);
    if (cached != null) return Optional.of(cached);
    Optional<Rate> rate = fetch(currency, date);
    rate.ifPresent(r -> cache.put(key, r));
    return rate;
  }

  protected Optional<Rate> fetch(String currency, LocalDate date) {
    try {
      Map<?, ?> body = http.get().uri("/{date}?base={currency}&symbols=INR", date, currency).retrieve().body(Map.class);
      if (body == null || !(body.get("rates") instanceof Map<?, ?> rates) || rates.get("INR") == null) {
        return Optional.empty();
      }
      return Optional.of(new Rate(new BigDecimal(String.valueOf(rates.get("INR"))),
          LocalDate.parse(String.valueOf(body.get("date")))));
    } catch (RuntimeException e) {
      log.warn("No {} rate for {}: {}", currency, date, e.getMessage());
      return Optional.empty();
    }
  }
}
