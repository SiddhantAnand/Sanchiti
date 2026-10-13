package com.sid.expense.service;

import com.sid.expense.ai.AiClient;
import com.sid.expense.ai.AiException;
import com.sid.expense.domain.Category;
import com.sid.expense.domain.Direction;
import com.sid.expense.domain.NarrationParser;
import com.sid.expense.repo.TransactionRepository;
import com.sid.expense.repo.TransactionRepository.PendingReason;
import com.sid.expense.service.TransactionService.CategoryUpdate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public class Categorizer {

  private static final Logger log = LoggerFactory.getLogger(Categorizer.class);

  private static final int BATCH_SIZE = 50;
  private static final int MAX_BATCHES = 10;

  private static final String SYSTEM = """
      You categorise personal spending entries for a person living in India.
      For every entry pick exactly one category from the allowed enum. Use "Other" only when nothing fits.
      Entries are usually merchant names taken from bank SMS/statements; they may be truncated or abbreviated
      (e.g. "HALLI THO"), so infer the most likely kind of business.
      "UPI transfer to a person" is usually Transfers unless the name is clearly a business.
      Hints: UPI/merchant names like Swiggy/Zomato = Food & Dining, BigBasket/Blinkit/Zepto/DMart = Groceries,
      Uber/Ola/Rapido/metro = Transport, petrol/diesel = Fuel, Netflix/Spotify/Prime = Subscriptions,
      electricity/mobile recharge/broadband/gas = Bills & Utilities, SIP/mutual fund/FD = Investments & Savings,
      CRED/credit card bill payments = Card Bill Payment.""";

  private static final Map<String, Object> RESPONSE_SCHEMA = Map.of(
      "type", "ARRAY",
      "items", Map.of(
          "type", "OBJECT",
          "properties", Map.of(
              "i", Map.of("type", "INTEGER"),
              // Self transfers are recognised from the account holder's name, which Gemini never sees.
              "category", Map.of("type", "STRING", "enum", Category.labels(Direction.DEBIT).stream()
                  .filter(l -> !l.equals(Category.SELF_TRANSFER.label())).toList())),
          "required", List.of("i", "category")));

  record Entry(int i, String entry) {}

  private final TransactionRepository repo;
  private final TransactionService transactions;
  private final AiClient ai;
  private final JsonMapper json;

  public Categorizer(TransactionRepository repo, TransactionService transactions, AiClient ai, JsonMapper json) {
    this.repo = repo;
    this.transactions = transactions;
    this.ai = ai;
    this.json = json;
  }

  /** For saves: a Gemini failure must not fail them; pending rows are retried by the next run. */
  public void categorizePendingQuietly(long userId) {
    try {
      categorizePending(userId);
    } catch (AiException e) {
      log.warn("[categorize] will retry later: {}", e.getMessage());
    }
  }

  /** Sends each distinct uncategorised merchant to Gemini once. Synchronised so overlapping calls never double-spend tokens. */
  public synchronized int categorizePending(long userId) {
    if (!ai.isEnabled()) return 0;
    int changed = 0;
    for (int batch = 0; batch < MAX_BATCHES; batch++) {
      List<PendingReason> pending = repo.pendingReasons(userId, BATCH_SIZE);
      if (pending.isEmpty()) break;

      List<Entry> entries = IntStream.range(0, pending.size())
          .mapToObj(i -> new Entry(i, NarrationParser.describeForAi(pending.get(i).reason())))
          .toList();
      Object data = ai.generate(SYSTEM, json.writeValueAsString(entries), RESPONSE_SCHEMA).data();
      if (!(data instanceof List<?> results)) throw new AiException("Gemini returned an unexpected categorisation format");

      List<CategoryUpdate> updates = new ArrayList<>();
      for (Object r : results) {
        if (r instanceof Map<?, ?> m && m.get("i") instanceof Number i && i.intValue() >= 0 && i.intValue() < pending.size()) {
          Category category = m.get("category") instanceof String s ? Category.fromLabel(s).orElse(Category.OTHER) : Category.OTHER;
          updates.add(new CategoryUpdate(pending.get(i.intValue()).reasonKey(), category));
        }
      }
      if (updates.isEmpty()) break;
      changed += transactions.applyAiCategories(userId, updates);
    }
    return changed;
  }
}
