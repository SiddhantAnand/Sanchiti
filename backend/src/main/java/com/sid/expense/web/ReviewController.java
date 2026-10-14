package com.sid.expense.web;

import com.sid.expense.config.AppProperties;
import com.sid.expense.domain.SmsParser;
import com.sid.expense.domain.Transaction;
import com.sid.expense.repo.ReviewRepository;
import com.sid.expense.repo.ReviewRepository.ReviewItem;
import com.sid.expense.repo.TransactionRepository;
import com.sid.expense.service.Categorizer;
import com.sid.expense.service.SmsReader;
import com.sid.expense.service.TransactionService;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Records that could not be stored as transactions, waiting to be fixed by hand. */
@RestController
@RequestMapping("/api/review")
public class ReviewController {

  record ReviewList(List<ReviewItem> items) {}

  record RetryResult(int saved, int alreadySaved, int remaining) {}

  private final ReviewRepository reviews;
  private final SmsReader reader;
  private final TransactionService service;
  private final TransactionRepository transactions;
  private final Categorizer categorizer;
  private final AppProperties props;

  public ReviewController(
      ReviewRepository reviews, SmsReader reader, TransactionService service, TransactionRepository transactions,
      Categorizer categorizer, AppProperties props) {
    this.reviews = reviews;
    this.reader = reader;
    this.service = service;
    this.transactions = transactions;
    this.categorizer = categorizer;
    this.props = props;
  }

  @GetMapping
  public ReviewList list(
      @RequestAttribute(AuthUser.ATTRIBUTE) AuthUser user, @RequestParam(defaultValue = "100") int limit) {
    if (limit < 1 || limit > 500) throw new ApiException(HttpStatus.BAD_REQUEST, "limit must be 1-500");
    return new ReviewList(reviews.list(user.id(), limit));
  }

  /** Saves the corrected record (the request body) as a transaction and removes it from review. */
  @PostMapping("/{id}/resolve")
  public ResponseEntity<Transaction> resolve(
      @RequestAttribute(AuthUser.ATTRIBUTE) AuthUser user, @PathVariable long id, @RequestBody Object body,
      @RequestParam(defaultValue = "false") boolean allowDuplicates) {
    if (!reviews.exists(user.id(), id)) throw new ApiException(HttpStatus.NOT_FOUND, "Review item not found");
    var checked = service.check(user.id(), body);
    if (checked.issue() != null) throw new ApiException(HttpStatus.BAD_REQUEST, checked.issue());
    var result = service.ingest(user.id(), user.name(), List.of(checked.input()), List.of(), allowDuplicates);
    if (result.insertedIds().isEmpty()) {
      throw new ApiException(HttpStatus.CONFLICT, "This transaction is already saved");
    }
    reviews.delete(user.id(), id);
    if (props.categorizeOnIngest()) categorizer.categorizePendingQuietly(user.id());
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(transactions.findById(user.id(), result.insertedIds().get(0)).orElseThrow());
  }

  /**
   * Reads every waiting SMS again (fixed rules, then Gemini) and files the ones that now work; ones already saved
   * are dropped. Anything Gemini calls "not a transaction" stays, relabelled, for the user to discard.
   */
  @PostMapping("/retry")
  public RetryResult retry(@RequestAttribute(AuthUser.ATTRIBUTE) AuthUser user) {
    List<ReviewRepository.ReviewItem> items = reviews.list(user.id(), 500).stream()
        .filter(item -> smsOf(item.payload()) != null)
        .toList();
    List<SmsReader.Sms> sms = items.stream().map(item -> {
      Map<?, ?> m = smsOf(item.payload());
      Object received = m.get("received") != null ? m.get("received") : m.get("receiver") != null ? m.get("receiver") : m.get("date");
      return new SmsReader.Sms(String.valueOf(m.get("body")), m.get("sender") == null ? null : String.valueOf(m.get("sender")),
          SmsParser.receivedDate(received == null ? null : String.valueOf(received)));
    }).toList();

    List<SmsReader.Outcome> outcomes = reader.read(user.id(), sms);
    int saved = 0;
    int alreadySaved = 0;
    for (int i = 0; i < items.size(); i++) {
      long id = items.get(i).id();
      switch (outcomes.get(i)) {
        case SmsReader.Read read when read.input() != null -> {
          var input = read.byAi() ? read.input().withNote(SmsReader.AI_NOTE) : read.input();
          var result = service.ingest(user.id(), user.name(), List.of(input), List.of(), false);
          if (result.insertedIds().isEmpty()) alreadySaved++;
          else saved++;
          reviews.delete(user.id(), id);
        }
        case SmsReader.Read read -> reviews.updateIssue(user.id(), id, read.issue());
        case SmsReader.Skipped skip -> reviews.updateIssue(user.id(), id, skip.why() + " — discard it if that's right");
        case SmsReader.NeedsReview needs -> reviews.updateIssue(user.id(), id, needs.issue());
      }
    }
    if (saved > 0 && props.categorizeOnIngest()) categorizer.categorizePendingQuietly(user.id());
    return new RetryResult(saved, alreadySaved, reviews.list(user.id(), 500).size());
  }

  /** Review payloads are the raw SMS, or {sms, parsed} when it was read but failed validation. */
  private static Map<?, ?> smsOf(Object payload) {
    if (!(payload instanceof Map<?, ?> p)) return null;
    Object sms = p.get("sms") instanceof Map<?, ?> inner ? inner : p;
    return sms instanceof Map<?, ?> m && m.get("body") instanceof String body && !body.isBlank() ? m : null;
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(@RequestAttribute(AuthUser.ATTRIBUTE) AuthUser user, @PathVariable long id) {
    if (reviews.delete(user.id(), id)) return ResponseEntity.noContent().build();
    throw new ApiException(HttpStatus.NOT_FOUND, "Review item not found");
  }
}
