package com.sid.expense.domain;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.Month;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns a raw bank SMS into the same fields a POST to /api/transactions carries, using fixed rules only. */
public final class SmsParser {

  public sealed interface Result permits Debit, Skip, Unreadable {}

  /** Keys match the /api/transactions contract so the result goes through the normal validation. */
  public record Debit(Map<String, Object> fields) implements Result {}

  public record Skip(String why) implements Result {}

  public record Unreadable(String why) implements Result {}

  private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
  private static final int I = Pattern.CASE_INSENSITIVE;

  private static final Pattern DEBIT = Pattern.compile(
      "\\b(?:debited|spent|sent|paid|deducted|withdrawn|purchase)\\b|\\bdebit(?:ed)?\\s+(?:for|of|by|with)\\b", I);
  private static final List<Map.Entry<Pattern, String>> NOT_A_DEBIT = List.of(
      Map.entry(Pattern.compile("\\b(?:otp|one[- ]time password|verification code)\\b", I), "OTP"),
      Map.entry(Pattern.compile("\\binvoice\\b|\\bstatement\\b|\\bpay (?:now|by)\\b|\\bplease pay\\b"
          + "|\\bignore if (?:already )?paid\\b|\\b(?:total|minimum|amount) due\\b", I), "Bill or reminder, not a payment"),
      Map.entry(Pattern.compile("\\bwill be (?:debited|deducted|charged)\\b|\\bscheduled\\b|\\bis due\\b|\\bdue (?:on|date)\\b"
          + "|\\breminder\\b", I), "Upcoming payment, not a debit yet"),
      Map.entry(Pattern.compile("\\b(?:failed|declined|unsuccessful)\\b", I), "Failed payment"),
      Map.entry(Pattern.compile("\\b(?:reversed|reversal|refund(?:ed)?)\\b", I), "Refund or reversal"),
      Map.entry(Pattern.compile("\\bunits?\\b.*\\ballot", I), "Mutual fund units notice"),
      Map.entry(Pattern.compile("\\bcredited to your\\b|\\bpayment\\b.*\\breceived\\b", I), "Credit, not a debit"));

  private static final Pattern AMOUNT = Pattern.compile("(?:\\bRs\\.?|\\bINR|₹)\\s*([\\d,]+(?:\\.\\d{1,2})?)", I);

  private static final Pattern ISO_DATE = Pattern.compile("\\b(20\\d{2})-(\\d{2})-(\\d{2})");
  private static final Pattern DAY_MON_YEAR =
      Pattern.compile("\\b(\\d{1,2})[- ]([A-Za-z]{3})[a-z]*[- ,]+(\\d{4}|\\d{2})\\b");
  private static final Pattern DAY_MONTH_YEAR = Pattern.compile("\\b(\\d{1,2})[/.-](\\d{1,2})[/.-](\\d{4}|\\d{2})\\b");

  private static final Pattern UPI_PATH = Pattern.compile("\\bUPI/(?:[A-Z0-9]{2,4}/)?(\\d{12})/([^/]+?)/", I);
  private static final Pattern LABELLED_REF = Pattern.compile(
      "\\b(?:ref(?:erence)?|rrn|utr|upi ref)(?:\\s*(?:no|number|id))?\\.?\\s*[:#-]?\\s*(\\d{12})\\b", I);
  private static final Pattern ANY_REF = Pattern.compile("(?<!\\d)(\\d{12})(?!\\d)");
  private static final Pattern REF_CONTEXT = Pattern.compile("\\b(?:upi|imps|neft|nach|ecmiss|vpa)\\b", I);

  private static final Pattern CARD = Pattern.compile(
      "\\bcard\\b(?:\\s*(?:no\\.?|number|ending(?:\\s*(?:with|in))?))?\\s*[:#]?\\s*[x*•.\\s]*(\\d{4})\\b", I);
  private static final Pattern NAMED_CARD = Pattern.compile("\\b(?:credit|debit) card\\b", I);
  private static final Pattern AUTOPAY = Pattern.compile(
      "\\b(?:umrn|nach|ecs|ecmiss|e-?mandate|mandate|auto[- ]?debit|autopay|standing instruction)\\b", I);
  private static final Pattern UPI = Pattern.compile("\\b(?:upi|vpa)\\b", I);
  private static final Pattern TRANSFER = Pattern.compile("\\b(imps|neft|rtgs)\\b", I);

  // A payee name ends at a date ("On 03/10/26" or a bare "04/10/26"), a bracket, a ref or a sentence end.
  private static final String PAYEE_END =
      "(?=\\s+on\\s+\\d|\\s+\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}\\b|\\s*\\(|\\s+ref\\b|\\s+(?:through|using)\\b|\\.\\s|\\.?$";

  private static final List<Pattern> PAYEE = List.of(
      // "spent using ICICI Bank Card XX7007 on 09-Oct-26 on MADHU WINES. Avl Limit"
      Pattern.compile("\\bon\\s+\\d{1,2}[- ][A-Za-z]{3}[a-z]*[- ]\\d{2,4}\\s+(?:on|at)\\s+(.+?)(?=\\.\\s|\\s+avl\\b|\\.?$)", I),
      // "At RENTOMOJO On 2026-10-08:12:20:39"
      Pattern.compile("\\bat\\s+(.+?)" + PAYEE_END + ")", I),
      // "To SIDDHANT ANAND On 03/10/26 Ref ...", "to a/c **0001 (UPI Ref No. ...)"
      Pattern.compile("\\bto\\s+(?:vpa\\s+)?(.+?)" + PAYEE_END + "|\\s+upi\\b|\\s+via\\b|\\s+from\\b)", I),
      Pattern.compile("\\btowards\\s+(.+?)(?=\\s+umrn\\b|\\s+ref\\b|\\.\\s|\\.?$)", I),
      // SBM: "infor :221006/600000000050/00000000/ECMISS/Klook Travel T"
      Pattern.compile("\\binfor?\\b\\s*[:-]?\\s*(.+?)(?=\\.\\s|\\.?$)", I));
  private static final Pattern IFSC_LIKE = Pattern.compile("^[A-Z]{4}0[A-Z0-9]{0,6}$");
  private static final Pattern LETTER = Pattern.compile("\\p{L}");

  private static final List<Map.Entry<Pattern, String>> SENDER_BANKS = List.of(
      Map.entry(Pattern.compile("HDFC", I), "HDFC Bank"),
      Map.entry(Pattern.compile("ICICI", I), "ICICI Bank"),
      Map.entry(Pattern.compile("SBM", I), "SBM Bank India"),
      Map.entry(Pattern.compile("SBI|SBIINB|ATMSBI", I), "State Bank of India"));
  private static final Pattern TEXT_BANK = Pattern.compile(
      "\\b(?:(HDFC|ICICI|SBM|Axis|Kotak|IDFC FIRST|IndusInd|Yes|Federal|Canara|Union|Punjab National|AU Small Finance)"
          + "\\s+Bank|(State Bank of India|Bank of Baroda|SBI))\\b",
      I);

  private SmsParser() {}

  /** {@code received} (the date the phone got the SMS) is used when the text has no usable date. */
  public static Result parse(String body, String sender, LocalDate received) {
    String text = body == null ? "" : body.replaceAll("\\s+", " ").trim();
    if (text.isEmpty()) return new Unreadable("Empty message");
    if (!DEBIT.matcher(text).find()) return new Skip("Not a debit message");
    for (var rule : NOT_A_DEBIT) {
      if (rule.getKey().matcher(text).find()) return new Skip(rule.getValue());
    }

    Matcher amount = AMOUNT.matcher(text);
    if (!amount.find()) return new Unreadable("No amount found");
    String value = amount.group(1).replace(",", "");

    LocalDate date = dateIn(text, received).orElse(received);
    if (date == null) return new Unreadable("No date found");

    Matcher upiPath = UPI_PATH.matcher(text);
    boolean hasUpiPath = upiPath.find();
    String upiRef = hasUpiPath ? upiPath.group(1) : ref(text);
    Matcher card = CARD.matcher(text);
    String cardLast4 = card.find() ? card.group(1) : null;

    String payee = hasUpiPath ? cleanPayee(upiPath.group(2)) : null;
    if (payee == null) payee = payee(text);
    if (payee == null) payee = NarrationParser.parse(text).merchant();
    if (payee == null || !LETTER.matcher(payee).find()) return new Unreadable("No payee found");

    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("date", date.toString());
    fields.put("amount", value);
    fields.put("reason", payee);
    fields.put("bankName", bank(sender, text));
    fields.put("paymentMethod", method(text, cardLast4, upiRef));
    fields.put("cardLast4", cardLast4);
    fields.put("upiRef", upiRef);
    return new Debit(fields);
  }

  /** Accepts YYYY-MM-DD or an ISO timestamp; timestamps are converted to Indian time before taking the date. */
  public static LocalDate receivedDate(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return OffsetDateTime.parse(value.trim()).atZoneSameInstant(INDIA).toLocalDate();
    } catch (DateTimeParseException e) {
      return Dates.parse(value).orElse(null);
    }
  }

  private static Optional<LocalDate> dateIn(String text, LocalDate received) {
    Matcher m = ISO_DATE.matcher(text);
    while (m.find()) {
      var d = date(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), received);
      if (d.isPresent()) return d;
    }
    m = DAY_MON_YEAR.matcher(text);
    while (m.find()) {
      Optional<Month> month = month(m.group(2));
      if (month.isEmpty()) continue;
      var d = date(year(m.group(3)), month.get().getValue(), Integer.parseInt(m.group(1)), received);
      if (d.isPresent()) return d;
    }
    m = DAY_MONTH_YEAR.matcher(text);
    while (m.find()) {
      var d = date(year(m.group(3)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)), received);
      if (d.isPresent()) return d;
    }
    return Optional.empty();
  }

  // A date far from when the SMS arrived is a misread (e.g. a reference number), so it is ignored.
  private static Optional<LocalDate> date(int year, int month, int day, LocalDate received) {
    try {
      LocalDate d = LocalDate.of(year, month, day);
      if (received != null && (d.isAfter(received.plusDays(1)) || d.isBefore(received.minusDays(62)))) {
        return Optional.empty();
      }
      return Optional.of(d);
    } catch (DateTimeException e) {
      return Optional.empty();
    }
  }

  private static int year(String y) {
    return y.length() == 2 ? 2000 + Integer.parseInt(y) : Integer.parseInt(y);
  }

  private static Optional<Month> month(String abbreviation) {
    String a = abbreviation.toUpperCase(Locale.ROOT);
    for (Month month : Month.values()) {
      if (month.name().startsWith(a)) return Optional.of(month);
    }
    return Optional.empty();
  }

  private static String ref(String text) {
    Matcher labelled = LABELLED_REF.matcher(text);
    if (labelled.find()) return labelled.group(1);
    if (!REF_CONTEXT.matcher(text).find()) return null;
    Matcher any = ANY_REF.matcher(text);
    return any.find() ? any.group(1) : null;
  }

  // The payee sits early in the message; later "to"/"at" phrases are helpline or blocking instructions.
  private static String payee(String text) {
    String best = null;
    int bestStart = Integer.MAX_VALUE;
    for (Pattern p : PAYEE) {
      Matcher m = p.matcher(text);
      while (m.find() && m.start() < bestStart) {
        String name = cleanPayee(m.group(1));
        if (name != null) {
          best = name;
          bestStart = m.start();
          break;
        }
      }
    }
    if (best != null && best.contains("/")) {
      String fromNarration = NarrationParser.parse(best).merchant();
      if (fromNarration != null) best = fromNarration;
    }
    return best;
  }

  private static String cleanPayee(String raw) {
    String name = raw.replaceAll("\\s+", " ").replaceAll("^[\\s:;,.-]+|[\\s:;,.-]+$", "");
    if (name.isEmpty() || name.length() > 80 || !LETTER.matcher(name).find()) return null;
    if (IFSC_LIKE.matcher(name.toUpperCase(Locale.ROOT)).matches()) return null;
    if (name.matches("(?i)(?:block|report|dispute|your|you)\\b.*")) return null;
    return name;
  }

  private static String bank(String sender, String text) {
    if (sender != null) {
      for (var entry : SENDER_BANKS) {
        if (entry.getKey().matcher(sender).find()) return entry.getValue();
      }
    }
    Matcher m = TEXT_BANK.matcher(text);
    if (!m.find()) return null;
    return m.group(1) != null ? m.group(1) : m.group(2);
  }

  private static String method(String text, String cardLast4, String upiRef) {
    if (cardLast4 != null || NAMED_CARD.matcher(text).find()) return "CARD";
    if (AUTOPAY.matcher(text).find()) return "AUTOPAY";
    if (UPI.matcher(text).find() || upiRef != null) return "UPI";
    Matcher transfer = TRANSFER.matcher(text);
    return transfer.find() ? transfer.group(1).toUpperCase(Locale.ROOT) : null;
  }
}
