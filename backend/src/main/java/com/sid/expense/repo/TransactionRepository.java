package com.sid.expense.repo;

import com.sid.expense.domain.Banks;
import com.sid.expense.domain.Category;
import com.sid.expense.domain.Direction;
import com.sid.expense.domain.Transaction;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TransactionRepository {

  private static final String COLUMNS = """
      id, date, amount_minor, currency, direction, reason, merchant, payment_method, upi_ref, card_last4, bank,
      category, category_source, note, created_at""";

  private static final RowMapper<Transaction> MAPPER = (rs, i) -> new Transaction(
      rs.getObject("id", UUID.class),
      rs.getObject("date", LocalDate.class),
      rs.getLong("amount_minor") / 100.0,
      rs.getString("currency"),
      Direction.valueOf(rs.getString("direction")),
      rs.getString("reason"),
      rs.getString("merchant"),
      rs.getString("payment_method"),
      rs.getString("upi_ref"),
      rs.getString("card_last4"),
      rs.getString("bank"),
      Category.fromLabel(rs.getString("category")).orElse(null),
      rs.getString("category_source"),
      rs.getString("note"),
      rs.getObject("created_at", OffsetDateTime.class));

  public record NewTransaction(
      long userId, UUID id, LocalDate date, long amountMinor, String currency, String direction, String reason,
      String reasonKey, String merchant, String paymentMethod, String upiRef, String cardLast4, String bank,
      String category, String categorySource, String dedupeKey, String note) {}

  /** Replaces every user-editable column; the dedupe key keeps identifying the originally received record. */
  public record EditedTransaction(
      long userId, UUID id, LocalDate date, long amountMinor, String currency, String direction, String reason,
      String reasonKey, String merchant, String paymentMethod, String upiRef, String cardLast4, String bank,
      String category, String categorySource, String note) {}

  public record KnownCategory(String category, String source) {}

  public record PendingReason(String reasonKey, String reason) {}

  /**
   * {@code minMinor}/{@code maxMinor} are inclusive amount bounds in paise; {@code kind} is spent, invested,
   * ownTransfers or received, the same split (INR only) as the summary's per-bank money in and out. Without a
   * {@code direction}, received means credits and the other kinds mean debits.
   */
  public record Filter(
      LocalDate from, LocalDate to, String category, String currency, String q, Long minMinor, Long maxMinor,
      String bank, String kind, Direction direction, int limit, int offset) {}

  public static final List<String> KINDS = List.of("spent", "invested", "ownTransfers", "received");

  private static final List<String> OWN_TRANSFER_LABELS = Arrays.stream(Category.values())
      .filter(Category::isOwnTransfer)
      .map(Category::label)
      .toList();
  private static final List<String> NOT_SPENDING_LABELS = Stream.concat(
          Stream.of(Category.INVESTMENTS_SAVINGS.label()), OWN_TRANSFER_LABELS.stream())
      .toList();

  private final JdbcClient jdbc;

  public TransactionRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Must run inside a transaction; serialises inserts so concurrent uploads can't both pass the duplicate check. */
  public void lockInserts() {
    jdbc.sql("SELECT pg_advisory_xact_lock(hashtext('transactions_insert'))").query().listOfRows();
  }

  public boolean existsByDedupeKey(long userId, String key) {
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM transactions WHERE user_id = ? AND dedupe_key = ?)")
        .params(userId, key)
        .query(Boolean.class)
        .single();
  }

  public record SameDayMatch(String reasonKey, String upiRef, String bank, String cardLast4) {}

  /** Rows with a parsed merchant on the same date, in the same direction, for exactly the same amount. */
  public List<SameDayMatch> sameDaySameAmount(
      long userId, Direction direction, LocalDate date, long amountMinor, String currency) {
    return jdbc.sql("""
        SELECT reason_key, upi_ref, bank, card_last4 FROM transactions
        WHERE user_id = ? AND direction = ? AND date = ? AND amount_minor = ? AND currency = ?
          AND merchant IS NOT NULL""")
        .params(userId, direction.name(), date, amountMinor, currency)
        .query((rs, i) -> new SameDayMatch(
            rs.getString("reason_key"), rs.getString("upi_ref"), rs.getString("bank"), rs.getString("card_last4")))
        .list();
  }

  public record Counterpart(UUID id, String category, String categorySource) {}

  /**
   * The other side of a move between the user's own accounts: opposite direction, same amount, within a day, and
   * not the same bank. The closest date wins.
   */
  public Optional<Counterpart> ownTransferCounterpart(
      long userId, UUID exceptId, Direction direction, LocalDate date, long amountMinor, String currency, String bank) {
    return jdbc.sql("""
        SELECT id, category, category_source FROM transactions
        WHERE user_id = :userId AND id <> :exceptId AND direction = :other AND amount_minor = :amountMinor
          AND currency = :currency AND date BETWEEN :from AND :to
          AND (CAST(:bank AS TEXT) IS NULL OR bank IS NULL OR bank <> :bank)
        ORDER BY ABS(date - CAST(:date AS DATE)), created_at
        LIMIT 1""")
        .param("userId", userId)
        .param("exceptId", exceptId)
        .param("other", (direction == Direction.DEBIT ? Direction.CREDIT : Direction.DEBIT).name())
        .param("amountMinor", amountMinor)
        .param("currency", currency)
        .param("from", date.minusDays(1))
        .param("to", date.plusDays(1))
        .param("date", date)
        .param("bank", bank)
        .query((rs, i) -> new Counterpart(
            rs.getObject("id", UUID.class), rs.getString("category"), rs.getString("category_source")))
        .optional();
  }

  /** Labels one row Self Transfer unless the user set its category by hand. */
  public void markSelfTransfer(long userId, UUID id) {
    jdbc.sql("""
        UPDATE transactions SET category = ?, category_source = 'rule'
        WHERE user_id = ? AND id = ? AND (category_source IS NULL OR category_source <> 'manual')""")
        .params(Category.SELF_TRANSFER.label(), userId, id)
        .update();
  }

  public record Span(LocalDate first, LocalDate last) {}

  /** Dates of the oldest and newest transaction, both null when there are none. */
  public Span span(long userId) {
    return jdbc.sql("SELECT MIN(date) AS first, MAX(date) AS last FROM transactions WHERE user_id = ?")
        .param(userId)
        .query((rs, i) -> new Span(rs.getObject("first", LocalDate.class), rs.getObject("last", LocalDate.class)))
        .single();
  }

  public Optional<KnownCategory> knownCategory(long userId, String reasonKey) {
    return jdbc.sql("SELECT category, source FROM reason_categories WHERE user_id = ? AND reason_key = ?")
        .params(userId, reasonKey)
        .query((rs, i) -> new KnownCategory(rs.getString("category"), rs.getString("source")))
        .optional();
  }

  public void insert(NewTransaction t) {
    jdbc.sql("""
        INSERT INTO transactions
          (user_id, id, date, amount_minor, currency, direction, reason, reason_key, merchant, payment_method, upi_ref,
           card_last4, bank, category, category_source, dedupe_key, note)
        VALUES (:userId, :id, :date, :amountMinor, :currency, :direction, :reason, :reasonKey, :merchant,
           :paymentMethod, :upiRef, :cardLast4, :bank, :category, :categorySource, :dedupeKey, :note)""")
        .paramSource(t)
        .update();
  }

  public void update(EditedTransaction t) {
    jdbc.sql("""
        UPDATE transactions SET date = :date, amount_minor = :amountMinor, currency = :currency,
          direction = :direction, reason = :reason, reason_key = :reasonKey, merchant = :merchant,
          payment_method = :paymentMethod, upi_ref = :upiRef, card_last4 = :cardLast4, bank = :bank,
          category = :category, category_source = :categorySource, note = :note
        WHERE id = :id AND user_id = :userId""")
        .paramSource(t)
        .update();
  }

  /** Hands the row (and its merchant's remembered category) back to Gemini. */
  public void clearCategory(long userId, UUID id, String reasonKey) {
    jdbc.sql("UPDATE transactions SET category = NULL, category_source = NULL WHERE user_id = ? AND id = ?")
        .params(userId, id)
        .update();
    jdbc.sql("DELETE FROM reason_categories WHERE user_id = ? AND reason_key = ?").params(userId, reasonKey).update();
  }

  public List<Transaction> findByIds(long userId, List<UUID> ids) {
    if (ids.isEmpty()) return List.of();
    Map<UUID, Transaction> byId = jdbc.sql(
            "SELECT " + COLUMNS + " FROM transactions WHERE user_id = :userId AND id IN (:ids)")
        .param("userId", userId)
        .param("ids", ids)
        .query(MAPPER)
        .list()
        .stream()
        .collect(Collectors.toMap(Transaction::id, Function.identity()));
    return ids.stream().map(byId::get).filter(t -> t != null).toList();
  }

  public Optional<Transaction> findById(long userId, UUID id) {
    return findByIds(userId, List.of(id)).stream().findFirst();
  }

  public Page list(long userId, Filter f) {
    List<String> where = new ArrayList<>();
    Map<String, Object> params = new HashMap<>();
    where.add("user_id = :userId");
    params.put("userId", userId);
    if (f.from() != null) {
      where.add("date >= :from");
      params.put("from", f.from());
    }
    if (f.to() != null) {
      where.add("date <= :to");
      params.put("to", f.to());
    }
    if (Category.UNCATEGORIZED.equals(f.category())) {
      where.add("category IS NULL");
    } else if (f.category() != null) {
      where.add("category = :category");
      params.put("category", f.category());
    }
    if (f.currency() != null) {
      where.add("currency = :currency");
      params.put("currency", f.currency());
    }
    if (f.q() != null) {
      where.add("(reason ILIKE :q ESCAPE '\\' OR note ILIKE :q ESCAPE '\\')");
      params.put("q", "%" + f.q().replaceAll("[\\\\%_]", "\\\\$0") + "%");
    }
    if (f.minMinor() != null) {
      where.add("amount_minor >= :minMinor");
      params.put("minMinor", f.minMinor());
    }
    if (f.maxMinor() != null) {
      where.add("amount_minor <= :maxMinor");
      params.put("maxMinor", f.maxMinor());
    }
    if (Banks.UNKNOWN.equals(f.bank())) {
      where.add("bank IS NULL");
    } else if (f.bank() != null) {
      where.add("bank = :bank");
      params.put("bank", f.bank());
    }
    if (f.kind() != null) {
      where.add("currency = 'INR'");
      Direction direction = f.direction() != null ? f.direction()
          : "received".equals(f.kind()) ? Direction.CREDIT : Direction.DEBIT;
      where.add("direction = :direction");
      params.put("direction", direction.name());
      switch (f.kind()) {
        case "invested" -> {
          where.add("category = :investments");
          params.put("investments", Category.INVESTMENTS_SAVINGS.label());
        }
        case "ownTransfers" -> {
          where.add("category IN (:own)");
          params.put("own", OWN_TRANSFER_LABELS);
        }
        default -> {
          where.add("(category IS NULL OR category NOT IN (:notSpending))");
          params.put("notSpending", NOT_SPENDING_LABELS);
        }
      }
    } else if (f.direction() != null) {
      where.add("direction = :direction");
      params.put("direction", f.direction().name());
    }
    String clause = " WHERE " + String.join(" AND ", where);

    int total = jdbc.sql("SELECT COUNT(*) FROM transactions" + clause).params(params).query(Integer.class).single();
    params.put("limit", f.limit());
    params.put("offset", f.offset());
    List<Transaction> items = jdbc.sql("SELECT " + COLUMNS + " FROM transactions" + clause
            + " ORDER BY date DESC, created_at DESC LIMIT :limit OFFSET :offset")
        .params(params)
        .query(MAPPER)
        .list();
    return new Page(total, items);
  }

  public record Page(int total, List<Transaction> items) {}

  public List<Transaction> inRange(long userId, LocalDate from, LocalDate to) {
    return jdbc.sql("SELECT " + COLUMNS
            + " FROM transactions WHERE user_id = ? AND date BETWEEN ? AND ? ORDER BY date, created_at")
        .params(userId, from, to)
        .query(MAPPER)
        .list();
  }

  public Optional<String> reasonKeyOf(long userId, UUID id) {
    return jdbc.sql("SELECT reason_key FROM transactions WHERE user_id = ? AND id = ?")
        .params(userId, id)
        .query(String.class)
        .optional();
  }

  /** Also relabels the merchant's other rows unless they were set by hand, and remembers it for new ones. */
  public void setManualCategory(long userId, UUID id, String reasonKey, String category) {
    jdbc.sql("UPDATE transactions SET category = ?, category_source = 'manual' WHERE user_id = ? AND id = ?")
        .params(category, userId, id)
        .update();
    jdbc.sql("""
        UPDATE transactions SET category = ?, category_source = 'manual'
        WHERE user_id = ? AND reason_key = ? AND (category_source IS NULL OR category_source <> 'manual')""")
        .params(category, userId, reasonKey)
        .update();
    jdbc.sql("""
        INSERT INTO reason_categories (user_id, reason_key, category, source) VALUES (?, ?, ?, 'manual')
        ON CONFLICT (user_id, reason_key)
        DO UPDATE SET category = EXCLUDED.category, source = 'manual', updated_at = now()""")
        .params(userId, reasonKey, category)
        .update();
  }

  /** Stores an AI category for a merchant (never overriding a manual one) and labels its uncategorised rows. */
  public int applyAiCategory(long userId, String reasonKey, String category) {
    jdbc.sql("""
        INSERT INTO reason_categories (user_id, reason_key, category, source) VALUES (?, ?, ?, 'ai')
        ON CONFLICT (user_id, reason_key) DO UPDATE SET category = EXCLUDED.category, updated_at = now()
        WHERE reason_categories.source <> 'manual'""")
        .params(userId, reasonKey, category)
        .update();
    return jdbc.sql("""
        UPDATE transactions SET category = ?, category_source = 'ai'
        WHERE user_id = ? AND reason_key = ? AND category IS NULL""")
        .params(category, userId, reasonKey)
        .update();
  }

  public boolean delete(long userId, UUID id) {
    return jdbc.sql("DELETE FROM transactions WHERE user_id = ? AND id = ?").params(userId, id).update() > 0;
  }

  /** Debits only: credits get their category from fixed rules or the user, never from Gemini. */
  public List<PendingReason> pendingReasons(long userId, int limit) {
    return jdbc.sql("""
        SELECT reason_key, MIN(reason) AS reason FROM transactions
        WHERE user_id = ? AND category IS NULL AND direction = 'DEBIT'
        GROUP BY reason_key ORDER BY MIN(created_at) LIMIT ?""")
        .params(userId, limit)
        .query((rs, i) -> new PendingReason(rs.getString("reason_key"), rs.getString("reason")))
        .list();
  }

  public int pendingCount(long userId) {
    return jdbc.sql("SELECT COUNT(*) FROM transactions WHERE user_id = ? AND category IS NULL AND direction = 'DEBIT'")
        .param(userId)
        .query(Integer.class)
        .single();
  }
}
