package com.sid.expense.domain;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public enum Category {
  FOOD_DINING("Food & Dining"),
  GROCERIES("Groceries"),
  TRANSPORT("Transport"),
  FUEL("Fuel"),
  SHOPPING("Shopping"),
  BILLS_UTILITIES("Bills & Utilities"),
  RENT_HOUSING("Rent & Housing"),
  HEALTH_MEDICAL("Health & Medical"),
  ENTERTAINMENT("Entertainment"),
  SUBSCRIPTIONS("Subscriptions"),
  TRAVEL("Travel"),
  EDUCATION("Education"),
  PERSONAL_CARE("Personal Care"),
  GIFTS_DONATIONS("Gifts & Donations"),
  INVESTMENTS_SAVINGS("Investments & Savings"),
  TRANSFERS("Transfers"),
  SELF_TRANSFER("Self Transfer"),
  CARD_BILL("Card Bill Payment"),
  FEES_CHARGES("Fees & Charges"),
  OTHER("Other"),
  SALARY("Salary"),
  REFUND_CASHBACK("Refund & Cashback"),
  INTEREST("Interest"),
  RECEIVED("Received");

  public static final String UNCATEGORIZED = "Uncategorized";

  private final String label;

  Category(String label) {
    this.label = label;
  }

  @JsonValue
  public String label() {
    return label;
  }

  public static Optional<Category> fromLabel(String label) {
    return Arrays.stream(values()).filter(c -> c.label.equals(label)).findFirst();
  }

  public static List<String> labels() {
    return Arrays.stream(values()).map(Category::label).toList();
  }

  public static List<String> labels(Direction direction) {
    return Arrays.stream(values()).filter(c -> c.fits(direction)).map(Category::label).toList();
  }

  /** Money coming in: salary, refunds, interest or anything else received. */
  public boolean isIncome() {
    return this == SALARY || this == REFUND_CASHBACK || this == INTEREST || this == RECEIVED;
  }

  /** Self Transfer is the only category used on both sides: out of one own account and into another. */
  public boolean fits(Direction direction) {
    if (this == SELF_TRANSFER) return true;
    return direction == Direction.CREDIT ? isIncome() : !isIncome();
  }

  /** Money moved to the user's own accounts or card bill; the card spends themselves are already counted. */
  public boolean isOwnTransfer() {
    return this == SELF_TRANSFER || this == CARD_BILL;
  }
}
