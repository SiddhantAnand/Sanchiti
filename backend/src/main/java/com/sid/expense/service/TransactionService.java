package com.sid.expense.service;

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

  public record CategoryUpdate(String reasonKey, String category) {}

  /** A validated item with its bank resolved to the canonical name, or the reason it needs review. */
  public record Checked(TransactionInput input, String issue) {}

  /** Columns worked out from the input plus whatever the narration parser finds in the reason. */
  private record Derived(
      String merchant, String reasonKey, String upiRef, String cardLast4, String method, String bank) {}

  private final TransactionRepository repo;
  private final ReviewRepository reviews;
  private final Catalog catalog;

  private static final String CREDIT_KEY = "in:";

  public TransactionService(TransactionRepository repo, ReviewRepository reviews, Catalog catalog) {
    this.repo = repo;
    this.reviews = reviews;
    this.catalog = catalog;
  }

  /** Validates one item for this user: their own banks and categories count as known. */
  public Checked check(long userId, Object item) {
    var parsed = TransactionInput.parse(item);
    if (!parsed.ok()) {
      return new Checked(null, parsed.issues().stream()
          .map(x -> x.field().isEmpty() ? x.message() : x.field() + ": " + x.message())
          .collect(Collectors.joining("; ")));
    }
    TransactionInput input = parsed.value();
    if (input.category() != null) {
      Optional<String> category = catalog.category(userId, input.category(), input.direction());
      if (category.isEmpty()) {
        return new Checked(null, "category: Unknown category for a " + input.direction().json());
      }
      input = input.withCategory(category.get());
    }
    if (input.bank() == null) return new Checked(input, null);
    TransactionInput valid = input;
    return catalog.banks(userId).resolve(input.bank())
        .map(canonical -> new Checked(valid.withBank(canonical), null))
        .orElseGet(() -> new Checked(null, "Unknown bank: " + valid.bank()));
  }

  private static Derived derive(TransactionInput t) {
    var n = NarrationParser.parse(t.reason());
    String upiRef = t.upiRef() != null ? t.upiRef() : n.upiRef();
    String cardLast4 = t.cardLast4() != null ? t.cardLast4() : n.cardLast4();
    String method = t.paymentMethod() != null ? t.paymentMethod() : n.method();
    if (method == null && cardLast4 != null) method = "CARD";
    if (method == null && upiRef != null) method = "UPI";
    // Credits get their own merchant keys, so a person you pay and who pays you back keep separate categories.
    String reasonKey = (t.isCredit() ? CREDIT_KEY : "")
        + Texts.normalizeReason(n.merchant() != null ? n.merchant() : t.reason());
    return new Derived(
        n.merchant(), reasonKey, upiRef, cardLast4, method, t.bank() != null ? t.bank() : n.bank());
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
      String dedupeKey = (t.isCredit() ? CREDIT_KEY : "") + (upiRef != null
          ? "upi:" + upiRef + "|" + t.date() + "|" + t.amountMinor()
          : sha256(t.date() + "|" + t.amountMinor() + "|" + t.currency() + "|" + t.reason().toLowerCase(Locale.ROOT)
              + (d.bank() != null ? "|" + d.bank().toLowerCase(Locale.ROOT) : "")
              + (d.cardLast4() != null ? "|" + d.cardLast4() : "")));

      if (!allowDuplicates
          && (repo.existsByDedupeKey(userId, dedupeKey) || isSamePaymentFromAnotherSource(userId, t, n, d))) {
        duplicates.add(index);
        continue;
      }

      Optional<KnownCategory> category = category(userId, userName, d, t);
      UUID id = UUID.randomUUID();
      repo.insert(new NewTransaction(
          userId, id, t.date(), t.amountMinor(), t.currency(), t.direction().name(), t.reason(), reasonKey,
          d.merchant(), d.method(), upiRef, d.cardLast4(), d.bank(),
          category.map(KnownCategory::category).orElse(null), category.map(KnownCategory::source).orElse(null),
          dedupeKey, t.note()));
      pairOwnTransfer(userId, id, t, d.bank(), category.map(KnownCategory::category).orElse(null),
          category.map(KnownCategory::source).orElse(null));
      ids.add(id);
    }
    return new IngestResult(ids, duplicates);
  }

  /**
   * A hand-set category for the merchant wins, then the own-money rules, then the sender's hint (credits), then what
   * Gemini said before. Credits never wait for Gemini: anything unrecognised is plain Received.
   */
  private Optional<KnownCategory> category(long userId, String userName, Derived d, TransactionInput t) {
    Optional<KnownCategory> known = repo.knownCategory(userId, d.reasonKey())
        .filter(k -> catalog.category(userId, k.category(), t.direction()).isPresent());
    if (known.filter(k -> "manual".equals(k.source())).isPresent()) return known;
    Optional<Category> rule = OwnMoney.categoryFor(d.merchant() != null ? d.merchant() : t.reason(), userName)
        .filter(c -> c.fits(t.direction()));
    if (rule.isPresent()) return Optional.of(new KnownCategory(rule.get().label(), "rule"));
    if (t.category() != null) return Optional.of(new KnownCategory(t.category(), "rule"));
    if (known.isPresent() || !t.isCredit()) return known;
    return Optional.of(new KnownCategory(Category.RECEIVED.label(), "rule"));
  }

  /**
   * Money moved between the user's own accounts shows up twice: debited from one bank, credited to another. When one
   * side is a Self Transfer, the other side becomes one too, so neither counts as spending or income.
   */
  private void pairOwnTransfer(
      long userId, UUID id, TransactionInput t, String bank, String category, String source) {
    var other = repo.ownTransferCounterpart(
        userId, id, t.direction(), t.date(), t.amountMinor(), t.currency(), bank);
    if (other.isEmpty()) return;
    boolean self = Category.SELF_TRANSFER.label().equals(category);
    boolean otherSelf = Category.SELF_TRANSFER.label().equals(other.get().category());
    if (self && !otherSelf) repo.markSelfTransfer(userId, other.get().id());
    else if (otherSelf && !self && !"manual".equals(source)) repo.markSelfTransfer(userId, id);
  }

  /**
   * Saves a hand edit. A new merchant or a flipped direction drops a non-manual category in favour of what is known
   * for the new merchant (or nothing, so Gemini picks one). A manual category that no longer fits the direction goes.
   */
  @Transactional
  public void edit(long userId, String userName, Transaction old, TransactionInput t) {
    Derived d = derive(t);
    String category = old.category();
    String source = old.categorySource();
    boolean merchantChanged = !d.reasonKey().equals(repo.reasonKeyOf(userId, old.id()).orElse(null));
    boolean fits = category == null || catalog.category(userId, category, t.direction()).isPresent();
    if ((merchantChanged && !"manual".equals(source)) || !fits) {
      Optional<KnownCategory> known = category(userId, userName, d, t);
      category = known.map(KnownCategory::category).orElse(null);
      source = known.map(KnownCategory::source).orElse(null);
    }
    repo.update(new EditedTransaction(
        userId, old.id(), t.date(), t.amountMinor(), t.currency(), t.direction().name(), t.reason(), d.reasonKey(),
        d.merchant(), d.method(), d.upiRef(), d.cardLast4(), d.bank(), category, source, t.note()));
  }

  /** Debits go back to Gemini; credits go back to their rule-based category. */
  @Transactional
  public void clearCategory(long userId, String userName, Transaction tx) {
    repo.reasonKeyOf(userId, tx.id()).ifPresent(key -> repo.clearCategory(userId, tx.id(), key));
    if (!tx.isCredit()) return;
    var input = new TransactionInput(tx.date(), tx.amountMinor(), tx.reason(), tx.currency(), tx.bank(),
        tx.paymentMethod(), tx.cardLast4(), tx.upiRef(), tx.note(), tx.direction(), null);
    Derived d = derive(input);
    category(userId, userName, d, input).ifPresent(k -> repo.update(new EditedTransaction(
        userId, tx.id(), tx.date(), tx.amountMinor(), tx.currency(), tx.direction().name(), tx.reason(),
        d.reasonKey(), d.merchant(), d.method(), d.upiRef(), d.cardLast4(), d.bank(), k.category(), k.source(),
        tx.note())));
  }

  /**
   * Bank SMS, statements and UPI apps describe one payment differently ("Debit - ... to APPLE MEDIA SERVICES" vs
   * "E-Mandate for APPLE MEDIA SERVICES"). Same day + amount + merchant is treated as one payment, unless the two
   * sides carry different UPI references, banks or cards.
   */
  private boolean isSamePaymentFromAnotherSource(
      long userId, TransactionInput t, NarrationParser.Narration n, Derived d) {
    if (n.merchant() == null) return false;
    return repo.sameDaySameAmount(userId, t.direction(), t.date(), t.amountMinor(), t.currency()).stream()
        .filter(m -> d.upiRef() == null || m.upiRef() == null)
        .filter(m -> !conflicts(d.bank(), m.bank()) && !conflicts(d.cardLast4(), m.cardLast4()))
        .anyMatch(m -> Texts.sameMerchant(d.reasonKey(), m.reasonKey()));
  }

  private static boolean conflicts(String a, String b) {
    return a != null && b != null && !a.equalsIgnoreCase(b);
  }

  /**
   * Manual override; also re-labels other non-manual transactions of the same merchant and remembers it. Marking one
   * side of a move between own accounts as Self Transfer marks the other side too.
   */
  /** {@code category} must already be one of the user's labels for the transaction's direction. */
  @Transactional
  public void setCategory(long userId, Transaction tx, String category) {
    repo.reasonKeyOf(userId, tx.id()).ifPresent(key -> repo.setManualCategory(userId, tx.id(), key, category));
    if (Category.SELF_TRANSFER.label().equals(category)) {
      repo.ownTransferCounterpart(
              userId, tx.id(), tx.direction(), tx.date(), tx.amountMinor(), tx.currency(), tx.bank())
          .ifPresent(other -> repo.markSelfTransfer(userId, other.id()));
    }
  }

  @Transactional
  public int applyAiCategories(long userId, List<CategoryUpdate> updates) {
    int changed = 0;
    for (CategoryUpdate u : updates) changed += repo.applyAiCategory(userId, u.reasonKey(), u.category());
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
