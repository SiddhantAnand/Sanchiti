package com.sid.expense.domain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Allow-list of real banks; each entry is "Canonical Name|alias|alias". Spellings that differ only by generic
 * words ("HDFC", "HDFC Bank", "HDFC Bank Ltd", "SBM BANK (INDIA)") resolve to the same canonical name.
 */
public final class Banks {

  /** Label for debits whose bank is not known, in totals and as a filter value. */
  public static final String UNKNOWN = "Unknown bank";

  private static final Set<String> GENERIC = Set.of("bank", "ltd", "limited", "pvt", "private", "the", "india", "co");

  private final Map<String, String> byKey = new HashMap<>();
  private final List<String> canonicalNames = new ArrayList<>();

  public Banks(List<String> entries) {
    for (String entry : entries == null ? List.<String>of() : entries) {
      String[] names = entry.split("\\|");
      String canonical = names[0].trim();
      if (canonical.isEmpty()) continue;
      canonicalNames.add(canonical);
      for (String name : names) {
        String key = key(name);
        if (!key.isEmpty()) byKey.put(key, canonical);
      }
    }
  }

  public List<String> canonicalNames() {
    return List.copyOf(canonicalNames);
  }

  /** Canonical name for a known bank ("HDFC" -> "HDFC Bank"), empty for anything else. */
  public Optional<String> resolve(String name) {
    String key = key(name);
    return key.isEmpty() ? Optional.empty() : Optional.ofNullable(byKey.get(key));
  }

  public static String key(String name) {
    if (name == null) return "";
    return Arrays.stream(name.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
        .filter(w -> !w.isEmpty() && !GENERIC.contains(w))
        .collect(Collectors.joining());
  }
}
