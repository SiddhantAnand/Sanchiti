package com.sid.expense.domain;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public record Transaction(
    UUID id,
    LocalDate date,
    double amount,
    String currency,
    Direction direction,
    String reason,
    String merchant,
    String paymentMethod,
    String upiRef,
    String cardLast4,
    String bank,
    /** A built-in category label or one the user added; null while uncategorised. */
    String category,
    String categorySource,
    String note,
    OffsetDateTime createdAt,
    /** Set when a foreign-currency spend was converted: what was charged, and the INR rate of {@code fxDate}. */
    Double fxAmount,
    String fxCurrency,
    Double fxRate,
    LocalDate fxDate) {

  public long amountMinor() {
    return Math.round(amount * 100);
  }

  public Long fxAmountMinor() {
    return fxAmount == null ? null : Math.round(fxAmount * 100);
  }

  public boolean isCredit() {
    return direction == Direction.CREDIT;
  }

  public Optional<Category> builtInCategory() {
    return Category.fromLabel(category);
  }

  public boolean isOwnTransfer() {
    return builtInCategory().filter(Category::isOwnTransfer).isPresent();
  }

  public boolean isInvestment() {
    return builtInCategory().filter(c -> c == Category.INVESTMENTS_SAVINGS).isPresent();
  }

  /** Display name used for grouping: the parsed merchant, else the raw reason. */
  public String payee() {
    return merchant != null ? merchant : reason;
  }
}
