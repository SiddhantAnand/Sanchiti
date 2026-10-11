package com.sid.expense.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts merchant/payment details from Indian bank narrations (UPI, card, SMS-style). */
public final class NarrationParser {

  public record Narration(
      String merchant,
      String method,
      String upiRef,
      String upiType,
      String vpa,
      String note,
      String bank,
      String cardLast4) {

    static final Narration EMPTY = new Narration(null, null, null, null, null, null, null, null);
  }

  private static final Set<String> UPI_TYPES = Set.of("P2M", "P2P", "P2A", "DR", "CR");
  private static final Set<String> NOISE =
      Set.of("upi", "payment", "pay", "paid", "na", "null", "none", "sent", "collect", "request", "transfer");
  // IFSC-style payee bank code, often truncated in narrations (e.g. YESB0YBLU, HDFC0MERU).
  private static final Pattern BANK_CODE = Pattern.compile("^[A-Z]{4}0[A-Z0-9]{0,6}$");
  private static final Pattern LETTER = Pattern.compile("\\p{L}");
  private static final Pattern UPI_PREFIX = Pattern.compile("^UPI[/-]", Pattern.CASE_INSENSITIVE);
  private static final Pattern REF = Pattern.compile("^\\d{10,}$");
  private static final Pattern CARD =
      Pattern.compile(
          "^(?:(?:spent|paid|debited|txn|transaction)\\s+(?:on|using|via|from|by|at)\\s+)?(.+?\\bbank)\\s+(?:credit\\s+|debit\\s+)?card\\s*(?:no\\.?\\s*)?[x*•]*(\\d{4})\\s*(?:[/:-]|\\bat\\b)\\s*(.+)$",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern ENTITY = Pattern.compile("\\bentity\\s*:\\s*(.+)$", Pattern.CASE_INSENSITIVE);
  // "Debit - HDFC Bank A/C 1122 to X", "Sent Rs.10 From HDFC Bank A/C *1122 To X"
  private static final Pattern ACCOUNT =
      Pattern.compile(
          "^(?:.*?\\b(?:from|debit(?:ed)?)\\b\\s*[-:]?\\s*)?(\\S.*?\\bbank)\\s+a/?c\\s*(?:no\\.?\\s*)?[x*•]*\\d{3,6}\\s+to\\s+(.+)$",
          Pattern.CASE_INSENSITIVE);
  // "SIP instalment ... in scheme Edelweiss Small Cap Fund - Regular Plan Growth"
  private static final Pattern SCHEME =
      Pattern.compile("\\bscheme\\s+(.+?)(?:\\s+-\\s+.*)?$", Pattern.CASE_INSENSITIVE);
  // "Payment to X", "Payment at X", "E-Mandate for X", "SIP for X"
  private static final Pattern PAYEE =
      Pattern.compile(
          "^(?:payment|paid|sent|transfer(?:red)?|e-?mandate|sip|autopay|standing instruction)\\b.*?\\b(?:to|at|for)\\s+(.+)$",
          Pattern.CASE_INSENSITIVE);

  private NarrationParser() {}

  // "Debit and UPI/P2M/...", "Investment Deduction and SIP/..."
  private static final Pattern SOURCE_PREFIX =
      Pattern.compile("^(?:debit|credit|investment deduction)\\s+and\\s+", Pattern.CASE_INSENSITIVE);
  private static final Pattern UPI_MANDATE = Pattern.compile("^UPI\\s+mandate\\s*/\\s*(.+)$", Pattern.CASE_INSENSITIVE);
  // "SIP/Edelweiss ...", "Stocks SIP/Groww ...", "221006/6276.../ECMISS/Klook Travel T", "E-Mandate/Groww ...",
  // "PAYMENT ALERT!/Indian Clearing Corporation Lt"
  private static final Pattern SLASH_PAYEE =
      Pattern.compile(
          "^(?:.*?/)?\\s*(stocks\\s+sip|sip|stocks|ecmiss|nach|ach|e-?mandate|payment\\s+alert!?)\\s*/\\s*(.+)$",
          Pattern.CASE_INSENSITIVE);

  public static Narration parse(String reason) {
    String text = SOURCE_PREFIX.matcher(clean(reason)).replaceFirst("");
    Matcher mandate = UPI_MANDATE.matcher(text);
    if (mandate.matches()) {
      return new Narration(merchant(mandate.group(1)), "AUTOPAY", null, null, null, null, null, null);
    }
    if (UPI_PREFIX.matcher(text).find()) return parseUpi(text);

    Matcher slash = SLASH_PAYEE.matcher(text);
    if (slash.matches()) {
      String kind = slash.group(1).toLowerCase(Locale.ROOT);
      String method = kind.endsWith("sip") || kind.endsWith("mandate") ? "AUTOPAY" : null;
      return new Narration(merchant(slash.group(2)), method, null, null, null, null, null, null);
    }

    Matcher card = CARD.matcher(text);
    if (card.matches()) {
      return new Narration(clean(card.group(3)), "CARD", null, null, null, null, clean(card.group(1)), card.group(2));
    }

    Matcher account = ACCOUNT.matcher(text);
    if (account.matches()) {
      return new Narration(merchant(account.group(2)), null, null, null, null, null, clean(account.group(1)), null);
    }

    Matcher entity = ENTITY.matcher(text);
    if (entity.find()) {
      return new Narration(clean(entity.group(1)), null, null, null, null, null, null, null);
    }

    Matcher scheme = SCHEME.matcher(text);
    if (scheme.find()) return payeeOnly(scheme.group(1));

    Matcher payee = PAYEE.matcher(text);
    if (payee.matches()) return payeeOnly(payee.group(1));

    return Narration.EMPTY;
  }

  private static Narration payeeOnly(String name) {
    return new Narration(merchant(name), null, null, null, null, null, null, null);
  }

  private static String merchant(String raw) {
    return clean(raw).replaceAll("[.\\s]+$", "");
  }

  /** Short, token-cheap description for the categoriser. */
  public static String describeForAi(String reason) {
    Narration n = parse(reason);
    // Without a known payment method the full text ("SIP for ...", "E-Mandate for ...") carries useful context.
    if (n.merchant() == null || n.method() == null) {
      String text = clean(reason);
      return text.length() > 150 ? text.substring(0, 150) : text;
    }
    String how = null;
    if ("UPI".equals(n.method())) {
      how =
          switch (n.upiType() == null ? "" : n.upiType()) {
            case "P2M" -> "UPI merchant payment";
            case "P2P", "P2A" -> "UPI transfer to a person";
            default -> "UPI payment";
          };
    } else if ("CARD".equals(n.method())) {
      how = "card payment";
    } else if ("AUTOPAY".equals(n.method())) {
      how = "autopay / mandate";
    }
    List<String> parts = new ArrayList<>();
    parts.add(n.merchant());
    if (how != null) parts.add("(" + how + ")");
    if (n.vpa() != null) parts.add(n.vpa());
    if (n.note() != null) parts.add("note: " + n.note());
    return String.join(" ", parts);
  }

  private static Narration parseUpi(String text) {
    String sep = text.contains("/") ? "/" : "-";
    String[] raw = text.split(Pattern.quote(sep));
    String type = null, ref = null, vpa = null, merchant = null;
    List<String> notes = new ArrayList<>();

    for (int i = 1; i < raw.length; i++) {
      String part = clean(raw[i]);
      if (part.isEmpty()) continue;
      String upper = part.toUpperCase(Locale.ROOT);
      if (type == null && UPI_TYPES.contains(upper)) type = upper;
      else if (ref == null && REF.matcher(part).matches()) ref = part;
      else if (vpa == null && part.contains("@")) vpa = part.toLowerCase(Locale.ROOT);
      else if (BANK_CODE.matcher(upper).matches()
          || !LETTER.matcher(part).find()
          || NOISE.contains(part.toLowerCase(Locale.ROOT))) continue;
      else if (merchant == null) merchant = part;
      else notes.add(part);
    }

    if (merchant == null && vpa != null) merchant = vpa.substring(0, vpa.indexOf('@'));
    String note = notes.isEmpty() ? null : String.join(" ", notes);
    return new Narration(merchant, "UPI", ref, type, vpa, note, null, null);
  }

  private static String clean(String s) {
    return s == null ? "" : s.replaceAll("\\s+", " ").trim();
  }
}
