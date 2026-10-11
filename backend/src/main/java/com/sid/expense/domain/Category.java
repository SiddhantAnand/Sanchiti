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
  OTHER("Other");

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

  /** Money moved to the user's own accounts or card bill; the card spends themselves are already counted. */
  public boolean isOwnTransfer() {
    return this == SELF_TRANSFER || this == CARD_BILL;
  }
}
