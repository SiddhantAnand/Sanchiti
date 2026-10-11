package com.sid.expense.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Spots payments that move the user's own money (to their own account, or paying a credit-card bill). */
public final class OwnMoney {

  private static final Pattern CARD_BILL = Pattern.compile(
      "\\bcred\\b|credit\\s*card\\s*(?:bill|payment)|\\bcc\\s*(?:bill|payment)\\b|\\bcard\\s*bill\\b",
      Pattern.CASE_INSENSITIVE);

  private OwnMoney() {}

  public static Optional<Category> categoryFor(String payee, String userName) {
    if (payee == null) return Optional.empty();
    if (CARD_BILL.matcher(payee).find()) return Optional.of(Category.CARD_BILL);
    if (isOwnName(payee, userName)) return Optional.of(Category.SELF_TRANSFER);
    return Optional.empty();
  }

  // Needs at least two name words so a payee who only shares a first name doesn't match.
  static boolean isOwnName(String payee, String userName) {
    if (userName == null) return false;
    List<String> payeeWords = words(payee);
    return payeeWords.size() >= 2 && words(userName).containsAll(payeeWords);
  }

  private static List<String> words(String text) {
    return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^\\p{L}]+")).filter(w -> !w.isEmpty()).toList();
  }
}
