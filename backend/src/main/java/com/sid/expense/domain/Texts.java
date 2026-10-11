package com.sid.expense.domain;

import java.util.Locale;
import java.util.regex.Pattern;

public final class Texts {

  private static final Pattern NON_LETTERS = Pattern.compile("[^\\p{L}\\s]+");
  private static final Pattern COMPANY_SUFFIX = Pattern.compile("\\b(pvt|private|ltd|limited|llp|inc)\\b");
  private static final Pattern SPACES = Pattern.compile("\\s+");

  private Texts() {}

  /** Key used to reuse a category for the same merchant (ignores ref numbers, punctuation, case, company suffixes). */
  public static String normalizeReason(String reason) {
    String lower = reason.toLowerCase(Locale.ROOT);
    String key = NON_LETTERS.matcher(lower).replaceAll(" ");
    key = COMPANY_SUFFIX.matcher(key).replaceAll(" ");
    key = SPACES.matcher(key).replaceAll(" ").trim();
    return key.isEmpty() ? lower.trim() : key;
  }

  /** Loose match on normalised merchant keys: equal, or the same first two words ("groww invest tech priv" ~ "groww invest tech stocks"). */
  public static boolean sameMerchant(String a, String b) {
    if (a.equals(b)) return true;
    String[] x = a.split(" ");
    String[] y = b.split(" ");
    if (x[0].length() < 3) return false;
    int k = Math.min(2, Math.min(x.length, y.length));
    for (int i = 0; i < k; i++) {
      if (!x[i].equals(y[i])) return false;
    }
    return true;
  }
}
