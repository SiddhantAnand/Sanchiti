package com.sid.expense.domain;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;
import java.util.Optional;

/** Money out of (DEBIT) or into (CREDIT) one of the user's accounts or cards. */
public enum Direction {
  DEBIT,
  CREDIT;

  @JsonValue
  public String json() {
    return name().toLowerCase(Locale.ROOT);
  }

  public static Optional<Direction> parse(String value) {
    if (value == null) return Optional.empty();
    return switch (value.trim().toLowerCase(Locale.ROOT)) {
      case "debit" -> Optional.of(DEBIT);
      case "credit" -> Optional.of(CREDIT);
      default -> Optional.empty();
    };
  }
}
