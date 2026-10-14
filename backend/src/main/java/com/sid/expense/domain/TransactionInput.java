package com.sid.expense.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Validated POST body item. Amounts are kept in minor units (paise) to avoid float errors. */
public record TransactionInput(
    LocalDate date, long amountMinor, String reason, String currency, String bank, String paymentMethod,
    String cardLast4, String upiRef, String note, Direction direction, String category) {

  public static final String BASE_CURRENCY = "INR";
  private static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000000");
  private static final Pattern NUMERIC = Pattern.compile("^\\d+(\\.\\d+)?$");
  private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");
  private static final Pattern UPI_REF = Pattern.compile("^[A-Za-z0-9@._/-]{6,64}$");

  public record Issue(String field, String message) {}

  public record Parsed(TransactionInput value, List<Issue> issues) {
    public boolean ok() {
      return issues.isEmpty();
    }
  }

  /**
   * API contract: {date, amount, reason, currency?, bankName? (or bank?), paymentMethod?, cardLast4?, upiRef?, note?,
   * direction? (debit|credit, default debit), category? (a starting category that fits the direction)}.
   */
  public static Parsed parse(Object item) {
    List<Issue> issues = new ArrayList<>();
    if (!(item instanceof Map<?, ?> map)) {
      return new Parsed(null, List.of(new Issue("", "Each transaction must be a JSON object")));
    }

    LocalDate date = null;
    if (map.get("date") instanceof String s) {
      date = Dates.parse(s).orElse(null);
    }
    if (blankToNull(map.get("date")) == null) issues.add(new Issue("date", "Date is missing"));
    else if (date == null) issues.add(new Issue("date", "Use YYYY-MM-DD, DD-MM-YYYY or an ISO 8601 timestamp"));

    long amountMinor = 0;
    BigDecimal amount = toDecimal(map.get("amount"));
    if (blankToNull(map.get("amount")) == null) {
      issues.add(new Issue("amount", "Amount is missing"));
    } else if (amount == null) {
      issues.add(new Issue("amount", "Amount must be a number"));
    } else {
      amountMinor = amount.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValue();
      if (amountMinor <= 0) issues.add(new Issue("amount", "Amount must be greater than 0"));
      else if (amount.compareTo(MAX_AMOUNT) > 0) issues.add(new Issue("amount", "Amount is too large"));
    }

    String reason = map.get("reason") instanceof String s ? s.trim() : null;
    if (reason == null || reason.isEmpty() || reason.length() > 200) {
      issues.add(new Issue("reason", "Reason must be 1-200 characters"));
    }

    String currency = BASE_CURRENCY;
    Object rawCurrency = map.get("currency");
    if (rawCurrency != null) {
      currency = rawCurrency instanceof String s ? s.trim().toUpperCase(Locale.ROOT) : "";
      if (!CURRENCY.matcher(currency).matches()) {
        issues.add(new Issue("currency", "Use a 3-letter ISO code such as INR or USD"));
      }
    }

    String bankField = map.get("bankName") != null ? "bankName" : "bank";
    String bank = blankToNull(map.get(bankField));
    if (bank != null && bank.length() > 60) issues.add(new Issue(bankField, "Bank name must be at most 60 characters"));

    // Blank strings are treated as "not provided" so senders can always include these keys.
    String paymentMethod = blankToNull(map.get("paymentMethod"));
    if (paymentMethod != null) {
      paymentMethod = paymentMethod.toUpperCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
      if (paymentMethod.length() > 30) {
        issues.add(new Issue("paymentMethod", "Payment method must be at most 30 characters, e.g. UPI, CARD, NETBANKING"));
      }
    }

    String cardLast4 = blankToNull(map.get("cardLast4"));
    if (cardLast4 != null) {
      String digits = cardLast4.replaceAll("\\D", "");
      if (digits.length() < 4) issues.add(new Issue("cardLast4", "cardLast4 must contain the last 4 digits, e.g. \"1234\""));
      else cardLast4 = digits.substring(digits.length() - 4);
    }

    String upiRef = blankToNull(map.get("upiRef"));
    if (upiRef != null) {
      upiRef = upiRef.replaceAll("\\s+", "");
      if (!UPI_REF.matcher(upiRef).matches()) {
        issues.add(new Issue("upiRef", "upiRef must be 6-64 characters (letters, digits, @ . _ - /)"));
      }
    }

    String note = blankToNull(map.get("note"));
    if (note != null && note.length() > 500) issues.add(new Issue("note", "Note must be at most 500 characters"));

    Direction direction = Direction.DEBIT;
    String rawDirection = blankToNull(map.get("direction"));
    if (rawDirection != null) {
      direction = Direction.parse(rawDirection).orElse(null);
      if (direction == null) issues.add(new Issue("direction", "direction must be debit or credit"));
    }

    String category = blankToNull(map.get("category"));
    if (category != null && category.length() > 60) issues.add(new Issue("category", "Category is too long"));

    return issues.isEmpty()
        ? new Parsed(
            new TransactionInput(
                date, amountMinor, reason, currency, bank, paymentMethod, cardLast4, upiRef, note, direction, category),
            List.of())
        : new Parsed(null, issues);
  }

  public TransactionInput withBank(String canonical) {
    return new TransactionInput(
        date, amountMinor, reason, currency, canonical, paymentMethod, cardLast4, upiRef, note, direction, category);
  }

  public TransactionInput withCategory(String label) {
    return new TransactionInput(
        date, amountMinor, reason, currency, bank, paymentMethod, cardLast4, upiRef, note, direction, label);
  }

  public TransactionInput withNote(String newNote) {
    return new TransactionInput(
        date, amountMinor, reason, currency, bank, paymentMethod, cardLast4, upiRef, newNote, direction, category);
  }

  public boolean isCredit() {
    return direction == Direction.CREDIT;
  }

  private static String blankToNull(Object v) {
    if (v == null) return null;
    String s = v.toString().trim();
    return s.isEmpty() ? null : s;
  }

  private static BigDecimal toDecimal(Object v) {
    if (v instanceof Number n) return new BigDecimal(n.toString());
    if (v instanceof String s && NUMERIC.matcher(s.trim()).matches()) return new BigDecimal(s.trim());
    return null;
  }
}
