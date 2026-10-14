package com.sid.expense.web;

import com.sid.expense.config.AppProperties;
import com.sid.expense.domain.Category;
import com.sid.expense.domain.Dates;
import com.sid.expense.domain.Direction;
import com.sid.expense.domain.SmsParser;
import com.sid.expense.domain.Transaction;
import com.sid.expense.domain.TransactionInput;
import com.sid.expense.repo.ReviewRepository;
import com.sid.expense.repo.TransactionRepository;
import com.sid.expense.repo.TransactionRepository.Filter;
import com.sid.expense.repo.TransactionRepository.Page;
import com.sid.expense.service.Categorizer;
import com.sid.expense.service.Catalog;
import com.sid.expense.service.SmsReader;
import com.sid.expense.service.TransactionService;
import com.sid.expense.service.TransactionService.IngestResult;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

  private static final int MAX_BATCH = 500;
  private static final Set<String> EDITABLE = Set.of(
      "date", "amount", "reason", "currency", "bankName", "paymentMethod", "cardLast4", "upiRef", "note", "category",
      "direction");

  record ReviewNote(int index, String issue) {}

  record IngestResponse(List<Transaction> inserted, List<Integer> duplicateIndexes) {}

  record SkipNote(int index, String why) {}

  record ParsedNote(int index, Map<String, Object> fields) {}

  /** Indexes refer to positions in the posted {@code messages} list. {@code parsed} is only filled for a dry run. */
  record SmsResponse(
      int messages, List<Transaction> inserted, List<Integer> duplicateIndexes, List<ReviewNote> review,
      List<SkipNote> skipped, List<ParsedNote> parsed) {}

  private final TransactionService service;
  private final SmsReader reader;
  private final Catalog catalog;
  private final TransactionRepository repo;
  private final Categorizer categorizer;
  private final AppProperties props;
  private final JsonMapper json;

  public TransactionController(
      TransactionService service, SmsReader reader, Catalog catalog, TransactionRepository repo,
      Categorizer categorizer, AppProperties props, JsonMapper json) {
    this.service = service;
    this.reader = reader;
    this.catalog = catalog;
    this.repo = repo;
    this.categorizer = categorizer;
    this.props = props;
    this.json = json;
  }

  /** Saves one transaction object or an array of them; any invalid item rejects the request with 400. */
  @PostMapping
  public ResponseEntity<IngestResponse> create(
      @RequestAttribute(AuthUser.ATTRIBUTE) AuthUser user, @RequestBody Object body,
      @RequestParam(defaultValue = "false") boolean allowDuplicates) {
    List<?> items = body instanceof List<?> list ? list : Collections.singletonList(body);
    if (items.size() > MAX_BATCH) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "Send at most " + MAX_BATCH + " transactions per request");
    }

    List<TransactionInput> valid = new ArrayList<>();
    for (int i = 0; i < items.size(); i++) {
      var checked = service.check(user.id(), items.get(i));
      if (checked.issue() != null) {
        String where = items.size() == 1 ? "" : "Item " + i + ": ";
        throw new ApiException(HttpStatus.BAD_REQUEST, where + checked.issue());
      }
      valid.add(checked.input());
    }

    IngestResult result = service.ingest(user.id(), user.name(), valid, List.of(), allowDuplicates);
    if (props.categorizeOnIngest() && !result.insertedIds().isEmpty()) categorizer.categorizePendingQuietly(user.id());
    var response = new IngestResponse(repo.findByIds(user.id(), result.insertedIds()), result.duplicateIndexes());
    return ResponseEntity.status(result.insertedIds().isEmpty() ? HttpStatus.OK : HttpStatus.CREATED).body(response);
  }

  /**
   * Accepts raw bank SMS as {@code {"messages": [{body, sender?, received?}]}} (or a bare array) and reads them with
   * {@link SmsParser}. Debits and credits are saved, anything else is skipped, unreadable ones go to review.
   * {@code dryRun=true} saves nothing.
   */
  @PostMapping("/sms")
  public ResponseEntity<SmsResponse> fromSms(
      @RequestAttribute(AuthUser.ATTRIBUTE) AuthUser user, @RequestBody(required = false) String rawBody,
      @RequestParam(defaultValue = "false") boolean dryRun) {
    List<?> messages = messages(rawBody);
    if (messages.size() > MAX_BATCH) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "Send at most " + MAX_BATCH + " messages per request");
    }

    List<TransactionInput> valid = new ArrayList<>();
    List<Integer> validIndexes = new ArrayList<>();
    List<ReviewRepository.Pending> forReview = new ArrayList<>();
    List<ReviewNote> review = new ArrayList<>();
    List<SkipNote> skipped = new ArrayList<>();
    List<ParsedNote> parsed = new ArrayList<>();
    List<Integer> smsIndexes = new ArrayList<>();
    List<SmsReader.Sms> sms = new ArrayList<>();
    for (int i = 0; i < messages.size(); i++) {
      if (messages.get(i) instanceof Map<?, ?> m) {
        smsIndexes.add(i);
        sms.add(new SmsReader.Sms(text(m.get("body")), text(m.get("sender")),
            SmsParser.receivedDate(text(firstPresent(m)))));
      } else {
        review.add(new ReviewNote(i, "Each message must be an object with a body"));
      }
    }
    List<SmsReader.Outcome> outcomes = reader.read(user.id(), sms);
    for (int k = 0; k < outcomes.size(); k++) {
      int i = smsIndexes.get(k);
      Object message = messages.get(i);
      switch (outcomes.get(k)) {
        case SmsReader.Skipped skip -> skipped.add(new SkipNote(i, skip.why()));
        case SmsReader.NeedsReview needs -> {
          forReview.add(new ReviewRepository.Pending(message, needs.issue()));
          review.add(new ReviewNote(i, needs.issue()));
        }
        case SmsReader.Read read -> {
          parsed.add(new ParsedNote(i, read.fields()));
          if (read.input() != null) {
            valid.add(read.byAi() ? read.input().withNote(SmsReader.AI_NOTE) : read.input());
            validIndexes.add(i);
          } else {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("sms", message);
            payload.put("parsed", read.fields());
            forReview.add(new ReviewRepository.Pending(payload, read.issue()));
            review.add(new ReviewNote(i, read.issue()));
          }
        }
      }
    }

    if (dryRun) {
      return ResponseEntity.ok(new SmsResponse(messages.size(), List.of(), List.of(), review, skipped, parsed));
    }
    IngestResult result = service.ingest(user.id(), user.name(), valid, forReview, false);
    if (props.categorizeOnIngest() && !result.insertedIds().isEmpty()) categorizer.categorizePendingQuietly(user.id());
    var response = new SmsResponse(
        messages.size(),
        repo.findByIds(user.id(), result.insertedIds()),
        result.duplicateIndexes().stream().map(validIndexes::get).toList(),
        review,
        skipped,
        List.of());
    return ResponseEntity.status(result.insertedIds().isEmpty() ? HttpStatus.OK : HttpStatus.CREATED).body(response);
  }

  private static String text(Object value) {
    return value == null ? null : value.toString();
  }

  private static Object firstPresent(Map<?, ?> sms) {
    for (String key : List.of("received", "receiver", "date")) {
      if (sms.get(key) != null) return sms.get(key);
    }
    return null;
  }

  /**
   * Shortcuts can only join texts, so besides {"messages": [...]} and [...] this also takes a single message object
   * and comma-joined objects without the surrounding brackets.
   */
  private List<?> messages(String rawBody) {
    String body = rawBody == null ? "" : rawBody.trim();
    if (body.isEmpty()) return List.of();
    Object parsed;
    try {
      parsed = json.readValue(body, Object.class);
    } catch (JacksonException first) {
      try {
        parsed = json.readValue("[" + body + "]", Object.class);
      } catch (JacksonException second) {
        throw new ApiException(HttpStatus.BAD_REQUEST, "Body must be valid JSON");
      }
    }
    if (parsed instanceof Map<?, ?> map && map.get("messages") instanceof List<?> list) return list;
    if (parsed instanceof Map<?, ?> map && map.containsKey("body")) return List.of(map);
    if (parsed instanceof List<?> list) return list;
    throw new ApiException(HttpStatus.BAD_REQUEST, "Body must be {\"messages\": [...]} or an array of messages");
  }

  @GetMapping
  public Page list(
      @RequestAttribute(AuthUser.ATTRIBUTE) AuthUser user,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) String category,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String bank,
      @RequestParam(required = false) String kind,
      @RequestParam(required = false) String direction,
      @RequestParam(defaultValue = "100") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    if (category != null && !Category.UNCATEGORIZED.equals(category) && !catalog.isCategory(user.id(), category)) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown category; see /api/categories");
    }
    if (kind != null && !TransactionRepository.KINDS.contains(kind)) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "kind must be one of " + TransactionRepository.KINDS);
    }
    Direction dir = direction == null || direction.isBlank() ? null : Direction.parse(direction)
        .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "direction must be debit or credit"));
    if (bank != null && bank.length() > 80) throw new ApiException(HttpStatus.BAD_REQUEST, "bank is too long");
    String cur = currency == null ? null : currency.trim().toUpperCase(Locale.ROOT);
    if (cur != null && !cur.matches("[A-Z]{3}")) throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid currency");
    String search = q == null || q.isBlank() ? null : q.trim();
    if (search != null && search.length() > 100) throw new ApiException(HttpStatus.BAD_REQUEST, "q is too long");
    if (limit < 1 || limit > 500 || offset < 0) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "limit must be 1-500 and offset >= 0");
    }
    long[] amount = search == null ? null : amountRange(search);
    return repo.list(user.id(), new Filter(date(from, "from"), date(to, "to"), category, cur,
        amount == null ? search : null, amount == null ? null : amount[0], amount == null ? null : amount[1],
        bank == null || bank.isBlank() ? null : bank.trim(), kind, dir, limit, offset));
  }

  /** Oldest and newest transaction dates, for the month and year pickers. */
  @GetMapping("/span")
  public TransactionRepository.Span span(@RequestAttribute(AuthUser.ATTRIBUTE) AuthUser user) {
    return repo.span(user.id());
  }

  private static final String NUMBER = "(\\d+(?:\\.\\d{1,2})?)";
  private static final Pattern EXACT = Pattern.compile(NUMBER);
  private static final Pattern AT_LEAST = Pattern.compile(">=?" + NUMBER);
  private static final Pattern AT_MOST = Pattern.compile("<=?" + NUMBER);
  private static final Pattern BETWEEN = Pattern.compile(NUMBER + "(?:-|to)" + NUMBER);

  /** Reads "2500", "₹2,500", ">1000", "<500" or "500-2000" as an inclusive [min, max] in paise; null for text. */
  static long[] amountRange(String search) {
    String s = search.toLowerCase(Locale.ROOT).replaceAll("₹|rs\\.?|inr|,|\\s", "");
    try {
      Matcher m;
      if ((m = EXACT.matcher(s)).matches()) return new long[] {paise(m.group(1)), paise(m.group(1))};
      if ((m = AT_LEAST.matcher(s)).matches()) return new long[] {paise(m.group(1)), Long.MAX_VALUE};
      if ((m = AT_MOST.matcher(s)).matches()) return new long[] {0, paise(m.group(1))};
      if ((m = BETWEEN.matcher(s)).matches()) {
        long a = paise(m.group(1));
        long b = paise(m.group(2));
        return new long[] {Math.min(a, b), Math.max(a, b)};
      }
    } catch (ArithmeticException tooLarge) {
      return null;
    }
    return null;
  }

  private static long paise(String amount) {
    return new BigDecimal(amount).movePointRight(2).longValueExact();
  }

  /**
   * Edits any of {@link #EDITABLE}. {@code category} is a label for a manual override, or null to hand the row back
   * to Gemini; other fields are validated exactly like a POST.
   */
  @PatchMapping("/{id}")
  public Transaction update(
      @RequestAttribute(AuthUser.ATTRIBUTE) AuthUser user, @PathVariable String id, @RequestBody Object body) {
    if (!(body instanceof Map<?, ?> patch) || patch.isEmpty()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "Body must be an object with fields to change");
    }
    List<String> unknown = patch.keySet().stream().map(String::valueOf).filter(k -> !EDITABLE.contains(k)).toList();
    if (!unknown.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "Cannot edit: " + String.join(", ", unknown));

    String requested = null;
    if (patch.get("category") != null) {
      if (!(patch.get("category") instanceof String s) || !catalog.isCategory(user.id(), s)) {
        throw new ApiException(HttpStatus.BAD_REQUEST, "category must be one of /api/categories or null");
      }
      requested = s;
    }
    Transaction tx = uuid(id).flatMap(u -> repo.findById(user.id(), u))
        .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Transaction not found"));
    Direction direction = tx.direction();
    if (patch.get("direction") != null) {
      direction = patch.get("direction") instanceof String s ? Direction.parse(s).orElse(null) : null;
      if (direction == null) throw new ApiException(HttpStatus.BAD_REQUEST, "direction must be debit or credit");
    }
    Direction dir = direction;
    String category = requested == null ? null : catalog.category(user.id(), requested, dir)
        .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
            patch.get("category") + " is not a " + dir.json() + " category"));

    if (patch.keySet().stream().anyMatch(k -> !"category".equals(k))) {
      Map<String, Object> merged = new HashMap<>();
      merged.put("date", tx.date().toString());
      merged.put("amount", tx.amount());
      merged.put("reason", tx.reason());
      merged.put("currency", tx.currency());
      merged.put("bankName", tx.bank());
      merged.put("paymentMethod", tx.paymentMethod());
      merged.put("cardLast4", tx.cardLast4());
      merged.put("upiRef", tx.upiRef());
      merged.put("note", tx.note());
      patch.forEach((k, v) -> merged.put(String.valueOf(k), v));
      merged.remove("category");
      merged.put("direction", direction.json());
      var checked = service.check(user.id(), merged);
      if (checked.issue() != null) throw new ApiException(HttpStatus.BAD_REQUEST, checked.issue());
      service.edit(user.id(), user.name(), tx, checked.input());
    }
    Transaction current = repo.findById(user.id(), tx.id()).orElseThrow();
    if (category != null) service.setCategory(user.id(), current, category);
    else if (patch.containsKey("category")) service.clearCategory(user.id(), user.name(), current);
    if (props.categorizeOnIngest() && category == null) categorizer.categorizePendingQuietly(user.id());
    return repo.findById(user.id(), tx.id()).orElseThrow();
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(@RequestAttribute(AuthUser.ATTRIBUTE) AuthUser user, @PathVariable String id) {
    if (uuid(id).map(u -> repo.delete(user.id(), u)).orElse(false)) return ResponseEntity.noContent().build();
    throw new ApiException(HttpStatus.NOT_FOUND, "Transaction not found");
  }

  static LocalDate date(String value, String name) {
    if (value == null) return null;
    return Dates.parse(value).orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Invalid date for '" + name + "'"));
  }

  private static Optional<UUID> uuid(String id) {
    try {
      return Optional.of(UUID.fromString(id));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
