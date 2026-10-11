package com.sid.expense.config;

import java.time.ZoneId;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app")
public record AppProperties(
    String timezone,
    boolean secureCookies,
    boolean categorizeOnIngest,
    boolean autoWeekly,
    /** "Canonical Name|alias|alias" entries; records naming any other bank go to review. */
    List<String> knownBanks,
    Owner owner,
    Gemini gemini) {

  /** The first admin account, created from these values on the first start. */
  public record Owner(String login, String name, String password, String phoneKey) {}

  public record Gemini(String apiKey, String model) {}

  public ZoneId zone() {
    return ZoneId.of(timezone);
  }
}
