package com.sid.expense.service;

import com.sid.expense.ai.AiClient;
import com.sid.expense.ai.AiException;
import com.sid.expense.config.AppProperties;
import com.sid.expense.domain.Banks;
import com.sid.expense.domain.Dates;
import com.sid.expense.domain.SmsParser;
import com.sid.expense.domain.TransactionInput;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads bank SMS: fixed rules first, then Gemini for the ones the rules can't read. Gemini's answer is only kept when
 * the amount, reference and card digits it returns actually appear in the SMS, so it can't invent a transaction.
 */
@Service
public class SmsReader {

  private static final Logger log = LoggerFactory.getLogger(SmsReader.class);

  public sealed interface Outcome permits Read, Skipped, NeedsReview {}

  /** {@code input} is null when the fields were read but failed validation; {@code issue} then says why. */
  public record Read(Map<String, Object> fields, TransactionInput input, String issue, boolean byAi) implements Outcome {}

  public record Skipped(String why) implements Outcome {}

  public record NeedsReview(String issue) implements Outcome {}

  public record Sms(String body, String sender, LocalDate received) {}

  /** Put on transactions Gemini read, so they are easy to double-check. */
  public static final String AI_NOTE = "Read from SMS by Gemini";

  private static final int BATCH = 25;
  private static final Pattern NUMBER = Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?");

  private static final String SYSTEM = """
      You read SMS from Indian banks, cards and UPI apps and extract the single completed transaction each one
      reports, from the account holder's point of view.
      - direction: "debit" when money left the holder's account or card (even if the text adds that the payee was
        "credited"), "credit" when money arrived in it.
      - isTransaction=false for OTPs, offers, applications, notices, reminders, bills not yet paid, and failed,
        scheduled or upcoming payments.
      - payee: who was paid (debit) or who sent the money (credit): a merchant, person, biller or UPI ID as written.
        For bank fees or charges use "<bank name> charges".
      - Copy amount, upiRef (a 12-digit UPI/IMPS reference) and cardLast4 exactly from the text; use null when the text
        does not contain them. Never guess.
      - date: YYYY-MM-DD from the text, or null when it has none.
      - bank: the holder's bank or card issuer named in the text or sender, else null.""";

  private static final Map<String, Object> NULLABLE_STRING = Map.of("type", "STRING", "nullable", true);
  private static final Map<String, Object> SCHEMA = Map.of(
      "type", "ARRAY",
      "items", Map.of(
          "type", "OBJECT",
          "properties", Map.of(
              "i", Map.of("type", "INTEGER"),
              "isTransaction", Map.of("type", "BOOLEAN"),
              "direction", Map.of("type", "STRING", "enum", List.of("debit", "credit"), "nullable", true),
              "amount", Map.of("type", "NUMBER", "nullable", true),
              "date", NULLABLE_STRING,
              "payee", NULLABLE_STRING,
              "bank", NULLABLE_STRING,
              "paymentMethod", Map.of("type", "STRING", "nullable", true, "enum",
                  List.of("UPI", "CARD", "NETBANKING", "IMPS", "NEFT", "RTGS", "AUTOPAY", "CASH")),
              "upiRef", NULLABLE_STRING,
              "cardLast4", NULLABLE_STRING),
          "required", List.of("i", "isTransaction")));

  private final TransactionService transactions;
  private final AiClient ai;
  private final JsonMapper json;
  private final Banks banks;

  public SmsReader(TransactionService transactions, AiClient ai, JsonMapper json, AppProperties props) {
    this.transactions = transactions;
    this.ai = ai;
    this.json = json;
    this.banks = new Banks(props.knownBanks());
  }

  /** One outcome per message, in order. */
  public List<Outcome> read(List<Sms> messages) {
    List<Outcome> outcomes = new ArrayList<>();
    List<Integer> unread = new ArrayList<>();
    for (int i = 0; i < messages.size(); i++) {
      Sms sms = messages.get(i);
      Outcome outcome = switch (SmsParser.parse(sms.body(), sms.sender(), sms.received())) {
        case SmsParser.Skip skip -> new Skipped(skip.why());
        case SmsParser.Unreadable unreadable -> new NeedsReview("SMS: " + unreadable.why());
        case SmsParser.Parsed parsed -> read(parsed.fields(), false);
      };
      if (outcome instanceof NeedsReview && sms.body() != null && !sms.body().isBlank()) unread.add(i);
      outcomes.add(outcome);
    }
    if (!unread.isEmpty() && ai.isEnabled()) {
      for (int start = 0; start < unread.size(); start += BATCH) {
        askGemini(messages, unread.subList(start, Math.min(unread.size(), start + BATCH)), outcomes);
      }
    }
    return outcomes;
  }

  private Read read(Map<String, Object> fields, boolean byAi) {
    var checked = transactions.check(fields);
    return new Read(fields, checked.issue() == null ? checked.input() : null, checked.issue(), byAi);
  }

  private void askGemini(List<Sms> messages, List<Integer> indexes, List<Outcome> outcomes) {
    List<Map<String, Object>> entries = new ArrayList<>();
    for (int k = 0; k < indexes.size(); k++) {
      Sms sms = messages.get(indexes.get(k));
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("i", k);
      entry.put("sms", sms.body().replaceAll("\\s+", " ").trim());
      entry.put("sender", sms.sender() == null ? "" : sms.sender());
      entry.put("received", sms.received() == null ? "" : sms.received().toString());
      entries.add(entry);
    }
    Object data;
    try {
      data = ai.generate(SYSTEM, json.writeValueAsString(entries), SCHEMA).data();
    } catch (AiException e) {
      log.warn("[sms] Gemini could not read {} message(s): {}", indexes.size(), e.getMessage());
      return;
    }
    if (!(data instanceof List<?> results)) return;
    for (Object r : results) {
      if (!(r instanceof Map<?, ?> m) || !(m.get("i") instanceof Number n)) continue;
      int k = n.intValue();
      if (k < 0 || k >= indexes.size()) continue;
      int index = indexes.get(k);
      Sms sms = messages.get(index);
      Outcome previous = outcomes.get(index);
      if (Boolean.FALSE.equals(m.get("isTransaction"))) {
        outcomes.set(index, new Skipped("Gemini: not a bank transaction"));
        continue;
      }
      Map<String, Object> fields = fieldsFrom(m, sms);
      outcomes.set(index, fields == null
          ? new NeedsReview(((NeedsReview) previous).issue() + "; Gemini couldn't read it either")
          : read(fields, true));
    }
  }

  /** Null unless Gemini returned an amount and payee that the SMS supports. */
  private Map<String, Object> fieldsFrom(Map<?, ?> m, Sms sms) {
    String text = sms.body();
    BigDecimal amount = m.get("amount") instanceof Number n ? new BigDecimal(n.toString()) : null;
    String payee = m.get("payee") instanceof String s && !s.isBlank() ? s.trim() : null;
    if (amount == null || amount.signum() <= 0 || payee == null || payee.length() > 80 || !appears(amount, text)) {
      return null;
    }
    LocalDate date = m.get("date") instanceof String s ? Dates.parse(s).orElse(null) : null;
    LocalDate received = sms.received();
    if (date == null || (received != null && (date.isAfter(received.plusDays(1))
        || date.isBefore(received.minusDays(62))))) {
      date = received;
    }
    if (date == null) return null;

    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("date", date.toString());
    fields.put("amount", amount.toPlainString());
    fields.put("reason", payee);
    fields.put("bankName", m.get("bank") instanceof String b ? banks.resolve(b).orElse(null) : null);
    fields.put("paymentMethod", m.get("paymentMethod") instanceof String p ? p : null);
    fields.put("upiRef", inText(m.get("upiRef"), text));
    fields.put("cardLast4", inText(m.get("cardLast4"), text));
    fields.put("direction", "credit".equals(m.get("direction")) ? "credit" : "debit");
    return fields;
  }

  private static boolean appears(BigDecimal amount, String text) {
    Matcher n = NUMBER.matcher(text);
    while (n.find()) {
      try {
        if (new BigDecimal(n.group().replace(",", "")).compareTo(amount) == 0) return true;
      } catch (NumberFormatException ignored) {
        // e.g. "1.2.3"
      }
    }
    return false;
  }

  private static String inText(Object value, String text) {
    if (!(value instanceof String s) || s.isBlank()) return null;
    return text.replaceAll("\\s+", "").contains(s.replaceAll("\\s+", "")) ? s.trim() : null;
  }
}
