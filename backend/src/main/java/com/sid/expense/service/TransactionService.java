package com.sid.expense.service;

import com.sid.expense.config.AppProperties;
import com.sid.expense.domain.Banks;
import com.sid.expense.domain.Category;
import com.sid.expense.domain.NarrationParser;
import com.sid.expense.domain.OwnMoney;
import com.sid.expense.domain.Texts;
import com.sid.expense.domain.Transaction;
import com.sid.expense.domain.TransactionInput;
import com.sid.expense.repo.ReviewRepository;
import com.sid.expense.repo.TransactionRepository;
import com.sid.expense.repo.TransactionRepository.EditedTransaction;
import com.sid.expense.repo.TransactionRepository.KnownCategory;
import com.sid.expense.repo.TransactionRepository.NewTransaction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransactionService {

  public record IngestResult(List<UUID> insertedIds, List<Integer> duplicateIndexes) {}

  public record CategoryUpdate(String reasonKey, Category category) {}

  /** A validated item with its bank resolved to the canonical name, or the reason it needs review. */
  public record Checked(TransactionInput input, String issue) {}

  /** Columns worked out from the input plus whatever the narration parser finds in the reason. */
  private record Derived(
      String merchant, String reasonKey, String upiRef, String cardLast4, String method, String bank) {}

  private final TransactionRepository repo;
  private final ReviewRepository reviews;
  private final Banks banks;

  public TransactionService(TransactionRepository repo, ReviewRepository reviews, AppProperties props) {
    this.repo = repo;
    this.reviews = reviews;
    this.banks = new Banks(props.knownBanks());
  }

  public Checked check(Object item) {
    var parsed = TransactionInput.parse(item);
    if (!parsed.ok()) {
      return new Checked(null, parsed.issues().stream()
          .map(x -> x.field().isEmpty() ? x.message() : x.field() + ": " + x.message())
          .collect(Collectors.joining("; ")));
    }
    TransactionInput input = parsed.value();
    if (input.bank() == null) return new Checked(input, null);
    return banks.resolve(input.bank())
        .map(canonical -> new Checked(input.withBank(canonical), null))
        .orElseGet(() -> new Checked(null, "Unknown bank: " + input.bank()));
  }

  private static Derived derive(TransactionInput t) {
    var n = NarrationParser.parse(t.reason());
    String upiRef = t.upiRef() != null ? t.upiRef() : n.upiRef();
    String cardLast4 = t.cardLast4() != null ? t.cardLast4() : n.cardLast4();
    String method = t.paymentMethod() != null ? t.paymentMethod() : n.method();
    if (method == null && cardLast4 != null) method = "CARD";
    if (method == null && upiRef != null) method = "UPI";
    return new Derived(
        n.merchant(), Texts.normalizeReason(n.merchant() != null ? n.merchant() : t.reason()), upiRef, cardLast4,
        method, t.bank() != null ? t.bank() : n.bank());
  }

  @Transactional
  public IngestResult ingest(
      long userId, String userName, List<TransactionInput> inputs, List<ReviewRepository.Pending> forReview,
      boolean allowDuplicates) {
    repo.lockInserts();
    forReview.forEach(p -> reviews.save(userId, p.payload(), p.issue()));
    List<UUID> ids = new ArrayList<>();
    List<Integer> duplicates = new ArrayList<>();

    for (int index = 0; index < inputs.size(); index++) {
      TransactionInput t = inputs.get(index);
      var n = NarrationParser.parse(t.reason());
      Derived d = derive(t);
      String reasonKey = d.reasonKey();
      String upiRef = d.upiRef();
      // Senders reuse refs (mandate IDs, alerts), so a ref only identifies a payment together with date and amount.
      String dedupeKey = upiRef != null
          ? "upi:" + upiRef + "|" + t.date() + "|" + t.amountMinor()
          : sha256(t.date() + "|" + t.amountMinor() + "|" + t.currency() + "|" + t.reason().toLowerCase(Locale.ROOT)
              + (d.bank() != null ? "|" + d.bank().toLowerCase(Locale.ROOT) : "")
              + (d.cardLast4() != null ? "|" + d.cardLast4() : ""));

      if (!allowDuplicates
          && (repo.existsByDedupeKey(userId, dedupeKey) || isSamePaymentFromAnotherSource(userId, t, n, d))) {
        duplicates.add(index);
        continue;
      }

      Optional<KnownCategory> category = category(userId, userName, d, t);
      UUID id = UUID.randomUUID();
      repo.insert(new NewTransaction(
          userId, id, t.date(), t.amountMinor(), t.currency(), t.reason(), reasonKey,
          d.merchant(), d.method(), upiRef, d.cardLast4(), d.bank(),
          category.map(KnownCategory::category).orElse(null), category.map(KnownCategory::source).orElse(null),
          dedupeKey, t.note()));
      ids.add(id);
    }
    return new IngestResult(ids, duplicates);
  }

  /** A hand-set category for the merchant wins, then the own-money rules, then what Gemini said before. */
  private Optional<KnownCategory> category(long userId, String userName, Derived d, TransactionInput t) {
    Optional<KnownCategory> known = repo.knownCategory(userId, d.reasonKey());
    if (known.filter(k -> "manual".equals(k.source())).isPresent()) return known;
    Optional<Category> rule = OwnMoney.categoryFor(d.merchant() != null ? d.merchant() : t.reason(), userName);
    if (rule.isPresent()) return Optional.of(new KnownCategory(rule.get().label(), "rule"));
    return known;
  }

  /**
   * Saves a hand edit. A new merchant drops a non-manual category in favour of what is known for the new merchant
   * (or nothing, so Gemini picks one).
   */
  @Transactional
  public void edit(long userId, String userName, Transaction old, TransactionInput t) {
    Derived d = derive(t);
    String category = old.category() == null ? null : old.category().label();
    String source = old.categorySource();
    boolean merchantChanged = !d.reasonKey().equals(repo.reasonKeyOf(userId, old.id()).orElse(null));
    if (merchantChanged && !"manual".equals(source)) {
      Optional<KnownCategory> known = category(userId, userName, d, t);
      category = known.map(KnownCategory::category).orElse(null);
      source = known.map(KnownCategory::source).orElse(null);
    }
    repo.update(new EditedTransaction(
        userId, old.id(), t.date(), t.amountMinor(), t.currency(), t.reason(), d.reasonKey(), d.merchant(),
        d.method(), d.upiRef(), d.cardLast4(), d.bank(), category, source, t.note()));
  }

  @Transactional
  public void clearCategory(long userId, UUID id) {
    repo.reasonKeyOf(userId, id).ifPresent(key -> repo.clearCategory(userId, id, key));
  }

  /**
   * Bank SMS, statements and UPI apps describe one payment differently ("Debit - ... to APPLE MEDIA SERVICES" vs
   * "E-Mandate for APPLE MEDIA SERVICES"). Same day + amount + merchant is treated as one payment, unless the two
   * sides carry different UPI references, banks or cards.
   */
  private boolean isSamePaymentFromAnotherSource(
      long userId, TransactionInput t, NarrationParser.Narration n, Derived d) {
    if (n.merchant() == null) return false;
    return repo.sameDaySameAmount(userId, t.date(), t.amountMinor(), t.currency()).stream()
        .filter(m -> d.upiRef() == null || m.upiRef() == null)
        .filter(m -> !conflicts(d.bank(), m.bank()) && !conflicts(d.cardLast4(), m.cardLast4()))
        .anyMatch(m -> Texts.sameMerchant(d.reasonKey(), m.reasonKey()));
  }

  private static boolean conflicts(String a, String b) {
    return a != null && b != null && !a.equalsIgnoreCase(b);
  }

  /** Manual override; also re-labels other non-manual transactions of the same merchant and remembers it. */
  @Transactional
  public void setCategory(long userId, UUID id, Category category) {
    repo.reasonKeyOf(userId, id).ifPresent(key -> repo.setManualCategory(userId, id, key, category.label()));
  }

  @Transactional
  public int applyAiCategories(long userId, List<CategoryUpdate> updates) {
    int changed = 0;
    for (CategoryUpdate u : updates) changed += repo.applyAiCategory(userId, u.reasonKey(), u.category().label());
    return changed;
  }

  public static String sha256(String text) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
