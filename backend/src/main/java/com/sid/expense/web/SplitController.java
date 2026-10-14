package com.sid.expense.web;

import com.sid.expense.domain.Dates;
import com.sid.expense.repo.SplitRepository.Person;
import com.sid.expense.service.SplitService;
import com.sid.expense.service.SplitService.GroupDetail;
import com.sid.expense.service.SplitService.GroupSummary;
import com.sid.expense.service.SplitService.NewExpense;
import com.sid.expense.service.SplitService.SplitException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Shared expense groups; see {@link SplitService} for who can see and change what. */
@RestController
@RequestMapping("/api/split")
public class SplitController {

  record People(List<Person> people) {}

  record Groups(List<GroupSummary> groups) {}

  record Created(long id) {}

  private final SplitService split;

  public SplitController(SplitService split) {
    this.split = split;
  }

  @GetMapping("/people")
  public People people() {
    return new People(split.people());
  }

  record Linked(List<UUID> transactionIds) {}

  /** Which of the user's transactions are already used in a split, to mark them in the list. */
  @GetMapping("/linked")
  public Linked linked(@RequestAttribute(AuthUser.ATTRIBUTE) AuthUser user) {
    return new Linked(split.linkedTransactions(user.id()));
  }

  @GetMapping("/groups")
  public Groups groups(@RequestAttribute(AuthUser.ATTRIBUTE) AuthUser user) {
    return new Groups(split.groups(user.id()));
  }

  /** {name, members?: [names]}; the creator is added as the first member. */
  @PostMapping("/groups")
  public ResponseEntity<Created> createGroup(
      @RequestAttribute(AuthUser.ATTRIBUTE) AuthUser user, @RequestBody Map<String, Object> body) {
    List<String> names = new ArrayList<>();
    if (body.get("members") instanceof List<?> list) list.forEach(x -> names.add(x == null ? null : String.valueOf(x)));
    long id = run(() -> split.createGroup(user.id(), user.name(), text(body, "name"), names));
    return ResponseEntity.status(HttpStatus.CREATED).body(new Created(id));
  }

  @GetMapping("/groups/{id}")
  public GroupDetail group(@RequestAttribute(AuthUser.ATTRIBUTE) AuthUser user, @PathVariable long id) {
    return run(() -> split.group(user.id(), id));
  }

  @PatchMapping("/groups/{id}")
  public GroupDetail rename(
      @RequestAttribute(AuthUser.ATTRIBUTE) AuthUser user, @PathVariable long id, @RequestBody Map<String, Object> body) {
    run(() -> split.renameGroup(user.id(), id, text(body, "name")));
    return group(user, id);
  }

  @DeleteMapping("/groups/{id}")
  public ResponseEntity<Void> deleteGroup(@RequestAttribute(AuthUser.ATTRIBUTE) AuthUser user, @PathVariable long id) {
    run(() -> split.deleteGroup(user.id(), id));
    return ResponseEntity.noContent().build();
  }

  /** {name, login?}. */
  @PostMapping("/groups/{id}/members")
  public GroupDetail addMember(
      @RequestAttribute(AuthUser.ATTRIBUTE) AuthUser user, @PathVariable long id, @RequestBody Map<String, Object> body) {
    run(() -> split.addMember(user.id(), id, text(body, "name"), text(body, "login")));
    return group(user, id);
  }

  /** {name?, login?}: a login links the member to that person; an empty login unlinks. */
  @PatchMapping("/groups/{id}/members/{memberId}")
  public GroupDetail updateMember(
      @RequestAttribute(AuthUser.ATTRIBUTE) AuthUser user, @PathVariable long id, @PathVariable long memberId,
      @RequestBody Map<String, Object> body) {
    run(() -> split.updateMember(user.id(), id, memberId, text(body, "name"), text(body, "login")));
    return group(user, id);
  }

  @DeleteMapping("/groups/{id}/members/{memberId}")
  public GroupDetail removeMember(
      @RequestAttribute(AuthUser.ATTRIBUTE) AuthUser user, @PathVariable long id, @PathVariable long memberId) {
    run(() -> split.removeMember(user.id(), id, memberId));
    return group(user, id);
  }

  /**
   * {description, amount, date, paidBy, split: equal|exact, members?: [ids], shares?: [{memberId, amount}],
   * transactionId?}.
   */
  @PostMapping("/groups/{id}/expenses")
  public GroupDetail addExpense(
      @RequestAttribute(AuthUser.ATTRIBUTE) AuthUser user, @PathVariable long id, @RequestBody Map<String, Object> body) {
    var expense = expense(body);
    run(() -> split.addExpense(user.id(), id, expense));
    return group(user, id);
  }

  /** Same body as adding; only the person who added the expense may edit it. */
  @PutMapping("/groups/{id}/expenses/{expenseId}")
  public GroupDetail updateExpense(
      @RequestAttribute(AuthUser.ATTRIBUTE) AuthUser user, @PathVariable long id, @PathVariable long expenseId,
      @RequestBody Map<String, Object> body) {
    var expense = expense(body);
    run(() -> split.updateExpense(user.id(), id, expenseId, expense));
    return group(user, id);
  }

  private static NewExpense expense(Map<String, Object> body) {
    List<Long> members = new ArrayList<>();
    if (body.get("members") instanceof List<?> list) list.forEach(x -> members.add(id(x, "members")));
    Map<Long, Long> shares = new LinkedHashMap<>();
    if (body.get("shares") instanceof List<?> list) {
      for (Object s : list) {
        if (s instanceof Map<?, ?> m) shares.merge(id(m.get("memberId"), "memberId"), paise(m.get("amount")), Long::sum);
      }
    }
    UUID tx = null;
    if (text(body, "transactionId") != null) {
      try {
        tx = UUID.fromString(text(body, "transactionId"));
      } catch (IllegalArgumentException e) {
        throw new ApiException(HttpStatus.BAD_REQUEST, "transactionId is not valid");
      }
    }
    return new NewExpense(text(body, "description"), paise(body.get("amount")), date(body), id(body.get("paidBy"), "paidBy"),
        "exact".equals(text(body, "split")) ? "exact" : "equal", members, shares, tx);
  }

  /** Deletes at once for the person who added it; for anyone else it asks that person to approve. */
  @DeleteMapping("/groups/{id}/expenses/{expenseId}")
  public GroupDetail deleteExpense(
      @RequestAttribute(AuthUser.ATTRIBUTE) AuthUser user, @PathVariable long id, @PathVariable long expenseId) {
    run(() -> split.deleteExpense(user.id(), id, expenseId));
    return group(user, id);
  }

  @PostMapping("/groups/{id}/expenses/{expenseId}/delete/approve")
  public GroupDetail approveDelete(
      @RequestAttribute(AuthUser.ATTRIBUTE) AuthUser user, @PathVariable long id, @PathVariable long expenseId) {
    run(() -> split.decideDelete(user.id(), id, expenseId, true));
    return group(user, id);
  }

  @PostMapping("/groups/{id}/expenses/{expenseId}/delete/reject")
  public GroupDetail rejectDelete(
      @RequestAttribute(AuthUser.ATTRIBUTE) AuthUser user, @PathVariable long id, @PathVariable long expenseId) {
    run(() -> split.decideDelete(user.id(), id, expenseId, false));
    return group(user, id);
  }

  /** {from, to, amount, date?}: "from paid to". */
  @PostMapping("/groups/{id}/settlements")
  public GroupDetail settle(
      @RequestAttribute(AuthUser.ATTRIBUTE) AuthUser user, @PathVariable long id, @RequestBody Map<String, Object> body) {
    long from = id(body.get("from"), "from");
    long to = id(body.get("to"), "to");
    long amount = paise(body.get("amount"));
    LocalDate date = body.get("date") == null ? null : date(body);
    run(() -> split.settle(user.id(), id, from, to, amount, date));
    return group(user, id);
  }

  @PostMapping("/groups/{id}/settlements/{settlementId}/approve")
  public GroupDetail approve(
      @RequestAttribute(AuthUser.ATTRIBUTE) AuthUser user, @PathVariable long id, @PathVariable long settlementId) {
    run(() -> split.decide(user.id(), id, settlementId, true));
    return group(user, id);
  }

  @PostMapping("/groups/{id}/settlements/{settlementId}/reject")
  public GroupDetail reject(
      @RequestAttribute(AuthUser.ATTRIBUTE) AuthUser user, @PathVariable long id, @PathVariable long settlementId) {
    run(() -> split.decide(user.id(), id, settlementId, false));
    return group(user, id);
  }

  @DeleteMapping("/groups/{id}/settlements/{settlementId}")
  public GroupDetail deleteSettlement(
      @RequestAttribute(AuthUser.ATTRIBUTE) AuthUser user, @PathVariable long id, @PathVariable long settlementId) {
    run(() -> split.deleteSettlement(user.id(), id, settlementId));
    return group(user, id);
  }

  private static <T> T run(Supplier<T> action) {
    try {
      return action.get();
    } catch (SplitException e) {
      throw new ApiException(HttpStatus.valueOf(e.status), e.getMessage());
    }
  }

  private static void run(Runnable action) {
    run(() -> {
      action.run();
      return null;
    });
  }

  private static String text(Map<?, ?> body, String key) {
    Object v = body.get(key);
    return v == null ? null : String.valueOf(v);
  }

  private static long id(Object v, String name) {
    if (v instanceof Number n) return n.longValue();
    if (v instanceof String s && s.matches("\\d{1,18}")) return Long.parseLong(s);
    throw new ApiException(HttpStatus.BAD_REQUEST, name + " must be an id");
  }

  private static long paise(Object v) {
    try {
      BigDecimal amount = new BigDecimal(String.valueOf(v).trim());
      if (amount.scale() > 2 || amount.compareTo(new BigDecimal("1000000000")) > 0) throw new NumberFormatException();
      return amount.movePointRight(2).longValueExact();
    } catch (RuntimeException e) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "amount must be a number with at most 2 decimals");
    }
  }

  private static LocalDate date(Map<?, ?> body) {
    return Dates.parse(text(body, "date") == null ? "" : text(body, "date"))
        .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "date must be YYYY-MM-DD"));
  }
}
