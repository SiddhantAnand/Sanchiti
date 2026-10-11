package com.sid.expense.domain;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record Transaction(
    UUID id,
    LocalDate date,
    double amount,
    String currency,
    String reason,
    String merchant,
    String paymentMethod,
    String upiRef,
    String cardLast4,
    String bank,
    Category category,
    String categorySource,
    String note,
    OffsetDateTime createdAt) {

  public long amountMinor() {
    return Math.round(amount * 100);
  }

  /** Display name used for grouping: the parsed merchant, else the raw reason. */
  public String payee() {
    return merchant != null ? merchant : reason;
  }
}
