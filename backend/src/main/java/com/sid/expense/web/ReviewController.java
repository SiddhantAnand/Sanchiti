package com.sid.expense.web;

import com.sid.expense.config.AppProperties;
import com.sid.expense.domain.Transaction;
import com.sid.expense.repo.ReviewRepository;
import com.sid.expense.repo.ReviewRepository.ReviewItem;
import com.sid.expense.repo.TransactionRepository;
import com.sid.expense.service.Categorizer;
import com.sid.expense.service.TransactionService;
import java.util.List;
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

  private final ReviewRepository reviews;
  private final TransactionService service;
  private final TransactionRepository transactions;
  private final Categorizer categorizer;
  private final AppProperties props;

  public ReviewController(
      ReviewRepository reviews, TransactionService service, TransactionRepository transactions,
      Categorizer categorizer, AppProperties props) {
    this.reviews = reviews;
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
    var checked = service.check(body);
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

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(@RequestAttribute(AuthUser.ATTRIBUTE) AuthUser user, @PathVariable long id) {
    if (reviews.delete(user.id(), id)) return ResponseEntity.noContent().build();
    throw new ApiException(HttpStatus.NOT_FOUND, "Review item not found");
  }
}
