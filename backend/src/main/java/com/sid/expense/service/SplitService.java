package com.sid.expense.service;

import com.sid.expense.domain.Direction;
import com.sid.expense.repo.SplitRepository;
import com.sid.expense.repo.SplitRepository.Expense;
import com.sid.expense.repo.SplitRepository.Group;
import com.sid.expense.repo.SplitRepository.Member;
import com.sid.expense.repo.SplitRepository.Settlement;
import com.sid.expense.repo.SplitRepository.Share;
import com.sid.expense.repo.TransactionRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Shared expense groups. A group is visible to its owner and to members linked to a login, and to nobody else; a
 * linked member sees only the group, never the owner's transactions. Nothing here touches the transactions table
 * except an optional, read-only link from an expense to one of its creator's own debits.
 */
@Service
public class SplitService {

  /** Thrown for bad input (400), missing/hidden things (404) and not-allowed actions (403). */
  public static class SplitException extends RuntimeException {
    public final int status;

    SplitException(int status, String message) {
      super(message);
      this.status = status;
    }
  }

  public record GroupSummary(long id, String name, String ownerName, boolean isOwner, int members, double myBalance) {}

  public record MemberView(long id, String name, String login, boolean isMe) {}

  public record ShareView(long memberId, double amount) {}

  /** {@code by} is the member who asked; the person who added the expense decides. */
  public record DeleteRequestView(Long by, boolean mine, boolean canDecide) {}

  /** {@code mine}: the user added it, so only they can edit or delete it. {@code addedBy} is a member id. */
  public record ExpenseView(
      long id, String description, double amount, LocalDate date, long paidBy, List<ShareView> shares, boolean mine,
      UUID transactionId, Long addedBy, DeleteRequestView deleteRequest) {}

  public record SettlementView(
      long id, long from, long to, double amount, LocalDate date, String status, boolean canDecide, boolean mine) {}

  /** {@code paid}: expenses they paid for; {@code share}: their part of all expenses; settlements count only in net. */
  public record Balance(long memberId, double net, double paid, double share) {}

  public record Debt(long from, long to, double amount) {}

  public record GroupDetail(
      long id, String name, String ownerName, boolean isOwner, Long myMemberId, List<MemberView> members,
      List<ExpenseView> expenses, List<SettlementView> settlements, List<Balance> balances, List<Debt> debts) {}

  /** {@code shares} is used for an exact split; {@code members} for an equal split. */
  public record NewExpense(
      String description, long amountMinor, LocalDate date, long paidBy, String split, List<Long> members,
      Map<Long, Long> shares, UUID transactionId) {}

  private static final int MAX_NAME = 60;

  private final SplitRepository repo;
  private final TransactionRepository transactions;

  public SplitService(SplitRepository repo, TransactionRepository transactions) {
    this.repo = repo;
    this.transactions = transactions;
  }

  public List<SplitRepository.Person> people() {
    return repo.people();
  }

  public List<UUID> linkedTransactions(long userId) {
    return repo.linkedTransactions(userId);
  }

  public List<GroupSummary> groups(long userId) {
    return repo.groupsFor(userId).stream().map(g -> {
      List<Member> members = repo.members(g.id());
      Map<Long, Long> net = net(g.id());
      long mine = members.stream().filter(m -> Objects.equals(m.userId(), userId))
          .mapToLong(m -> net.getOrDefault(m.id(), 0L)).sum();
      return new GroupSummary(g.id(), g.name(), g.ownerName(), g.ownerId() == userId, members.size(), major(mine));
    }).toList();
  }

  @Transactional
  public long createGroup(long userId, String userName, String name, List<String> memberNames) {
    long id = repo.createGroup(userId, clean(name, "Group name"));
    repo.addMember(id, userName, userId);
    for (String member : memberNames == null ? List.<String>of() : memberNames) {
      if (member != null && !member.isBlank()) repo.addMember(id, clean(member, "Name"), null);
    }
    return id;
  }

  public GroupDetail group(long userId, long groupId) {
    Group g = visible(userId, groupId);
    List<Member> members = repo.members(groupId);
    Long me = members.stream().filter(m -> Objects.equals(m.userId(), userId)).map(Member::id).findFirst().orElse(null);
    Map<Long, List<ShareView>> sharesByExpense = new HashMap<>();
    Map<Long, Long> shareTotal = new HashMap<>();
    for (Share s : repo.shares(groupId)) {
      sharesByExpense.computeIfAbsent(s.expenseId(), k -> new ArrayList<>()).add(new ShareView(s.memberId(), major(s.amountMinor())));
      shareTotal.merge(s.memberId(), s.amountMinor(), Long::sum);
    }
    List<Expense> rawExpenses = repo.expenses(groupId);
    Map<Long, Long> paidTotal = new HashMap<>();
    rawExpenses.forEach(e -> paidTotal.merge(e.paidBy(), e.amountMinor(), Long::sum));
    Map<Long, Long> memberByUser = members.stream().filter(m -> m.userId() != null)
        .collect(Collectors.toMap(Member::userId, Member::id));
    List<ExpenseView> expenses = rawExpenses.stream()
        .map(e -> {
          boolean mine = Objects.equals(e.createdBy(), userId);
          DeleteRequestView request = e.deleteRequested()
              ? new DeleteRequestView(memberByUser.get(e.deleteRequestedBy()), Objects.equals(e.deleteRequestedBy(), userId),
                  decider(g, members, e) == userId)
              : null;
          return new ExpenseView(e.id(), e.description(), major(e.amountMinor()), e.date(), e.paidBy(),
              sharesByExpense.getOrDefault(e.id(), List.of()), mine, mine ? e.transactionId() : null,
              memberByUser.get(e.createdBy()), request);
        })
        .toList();
    Map<Long, Member> byId = members.stream().collect(Collectors.toMap(Member::id, m -> m));
    List<SettlementView> settlements = repo.settlements(groupId).stream()
        .map(s -> new SettlementView(s.id(), s.fromMember(), s.toMember(), major(s.amountMinor()), s.date(), s.status(),
            "PENDING".equals(s.status()) && approver(g, byId.get(s.toMember())) == userId,
            Objects.equals(s.createdBy(), userId)))
        .toList();
    Map<Long, Long> net = net(groupId);
    List<Balance> balances = members.stream().map(m -> new Balance(m.id(), major(net.getOrDefault(m.id(), 0L)),
        major(paidTotal.getOrDefault(m.id(), 0L)), major(shareTotal.getOrDefault(m.id(), 0L)))).toList();
    return new GroupDetail(g.id(), g.name(), g.ownerName(), g.ownerId() == userId, me,
        members.stream().map(m -> new MemberView(m.id(), m.name(), m.login(), Objects.equals(m.userId(), userId))).toList(),
        expenses, settlements, balances, debts(net));
  }

  public void renameGroup(long userId, long groupId, String name) {
    owned(userId, groupId);
    repo.renameGroup(groupId, clean(name, "Group name"));
  }

  public void deleteGroup(long userId, long groupId) {
    owned(userId, groupId);
    repo.deleteGroup(groupId);
  }

  /** Owner only. {@code login} links the member to an existing login; that person then sees this group. */
  @Transactional
  public void addMember(long userId, long groupId, String name, String login) {
    owned(userId, groupId);
    Long linked = linkTarget(groupId, login, null);
    repo.addMember(groupId, clean(name, "Name"), linked);
  }

  /** Owner only. A blank {@code login} unlinks; the owner's own entry always stays linked to them. */
  @Transactional
  public void updateMember(long userId, long groupId, long memberId, String name, String login) {
    Group g = owned(userId, groupId);
    Member m = member(groupId, memberId);
    Long linked = Objects.equals(m.userId(), g.ownerId()) ? Long.valueOf(g.ownerId()) : linkTarget(groupId, login, memberId);
    repo.updateMember(memberId, name == null ? m.name() : clean(name, "Name"), linked);
  }

  public void removeMember(long userId, long groupId, long memberId) {
    Group g = owned(userId, groupId);
    Member m = member(groupId, memberId);
    if (Objects.equals(m.userId(), g.ownerId())) throw new SplitException(400, "You can't remove yourself from your own group");
    if (repo.memberInUse(memberId)) {
      throw new SplitException(409, m.name() + " has expenses or settlements in this group; remove those first");
    }
    repo.deleteMember(memberId);
  }

  @Transactional
  public long addExpense(long userId, long groupId, NewExpense e) {
    visible(userId, groupId);
    Map<Long, Long> shares = checked(userId, groupId, e);
    long id = repo.addExpense(groupId, clean(e.description(), "Description"), e.amountMinor(), e.date(), e.paidBy(),
        e.transactionId(), userId);
    shares.forEach((member, amount) -> repo.addShare(id, member, amount));
    return id;
  }

  /** Only the person who added the expense can change it. */
  @Transactional
  public void updateExpense(long userId, long groupId, long expenseId, NewExpense e) {
    visible(userId, groupId);
    if (!Objects.equals(expense(groupId, expenseId).createdBy(), userId)) {
      throw new SplitException(403, "Only the person who added this expense can edit it");
    }
    Map<Long, Long> shares = checked(userId, groupId, e);
    repo.updateExpense(expenseId, clean(e.description(), "Description"), e.amountMinor(), e.date(), e.paidBy(),
        e.transactionId());
    repo.deleteShares(expenseId);
    shares.forEach((member, amount) -> repo.addShare(expenseId, member, amount));
  }

  /** Validates an expense and works out each member's share. */
  private Map<Long, Long> checked(long userId, long groupId, NewExpense e) {
    Set<Long> memberIds = repo.members(groupId).stream().map(Member::id).collect(Collectors.toSet());
    if (e.amountMinor() <= 0) throw new SplitException(400, "Amount must be greater than 0");
    if (!memberIds.contains(e.paidBy())) throw new SplitException(400, "Pick who paid from the group");
    if (e.date() == null) throw new SplitException(400, "Date is missing");
    if (e.transactionId() != null) {
      var tx = transactions.findById(userId, e.transactionId())
          .filter(t -> t.direction() == Direction.DEBIT)
          .orElseThrow(() -> new SplitException(400, "That transaction isn't one of your debits"));
      if (tx.amountMinor() < e.amountMinor()) {
        throw new SplitException(400, "The split amount can't be more than the transaction");
      }
    }

    Map<Long, Long> shares = new LinkedHashMap<>();
    if ("exact".equals(e.split())) {
      if (e.shares() == null || e.shares().isEmpty()) throw new SplitException(400, "Enter how much each person owes");
      for (var s : e.shares().entrySet()) {
        if (!memberIds.contains(s.getKey())) throw new SplitException(400, "Everyone in the split must be in the group");
        if (s.getValue() < 0) throw new SplitException(400, "Shares can't be negative");
        if (s.getValue() > 0) shares.put(s.getKey(), s.getValue());
      }
      long sum = shares.values().stream().mapToLong(Long::longValue).sum();
      if (sum != e.amountMinor()) {
        throw new SplitException(400, "Shares add up to " + major(sum) + ", not " + major(e.amountMinor()));
      }
    } else {
      List<Long> people = e.members() == null ? List.of() : e.members().stream().distinct().toList();
      if (people.isEmpty()) throw new SplitException(400, "Pick at least one person to split with");
      if (!memberIds.containsAll(people)) throw new SplitException(400, "Everyone in the split must be in the group");
      long each = e.amountMinor() / people.size();
      long left = e.amountMinor() - each * people.size();
      for (Long p : people) {
        shares.put(p, each + (left-- > 0 ? 1 : 0));
      }
    }
    return shares;
  }

  /** The person who added it deletes at once; anyone else's delete waits for that person's approval. */
  @Transactional
  public boolean deleteExpense(long userId, long groupId, long expenseId) {
    Group g = visible(userId, groupId);
    Expense e = expense(groupId, expenseId);
    if (decider(g, repo.members(groupId), e) == userId) {
      repo.deleteExpense(groupId, expenseId);
      return true;
    }
    if (!repo.requestDelete(groupId, expenseId, userId)) {
      throw new SplitException(409, "Someone has already asked to delete this expense");
    }
    return false;
  }

  @Transactional
  public void decideDelete(long userId, long groupId, long expenseId, boolean approve) {
    Group g = visible(userId, groupId);
    Expense e = expense(groupId, expenseId);
    if (!e.deleteRequested()) throw new SplitException(409, "Nobody has asked to delete this expense");
    if (decider(g, repo.members(groupId), e) != userId) {
      throw new SplitException(403, "Only the person who added this expense can approve deleting it");
    }
    if (approve) {
      repo.deleteExpense(groupId, expenseId);
    } else {
      repo.clearDeleteRequest(groupId, expenseId);
    }
  }

  /** Whoever added the expense; the owner stands in if that person can no longer see the group. */
  private static long decider(Group g, List<Member> members, Expense e) {
    Long creator = e.createdBy();
    boolean stillIn = creator != null
        && (creator == g.ownerId() || members.stream().anyMatch(m -> creator.equals(m.userId())));
    return stillIn ? creator : g.ownerId();
  }

  private Expense expense(long groupId, long expenseId) {
    return repo.expenses(groupId).stream().filter(x -> x.id() == expenseId).findFirst()
        .orElseThrow(() -> new SplitException(404, "Expense not found"));
  }

  /** Pending until the person paid approves; approved at once when that person is the one recording it. */
  public long settle(long userId, long groupId, long from, long to, long amountMinor, LocalDate date) {
    Group g = visible(userId, groupId);
    Member payee = member(groupId, to);
    member(groupId, from);
    if (from == to) throw new SplitException(400, "Pick two different people");
    if (amountMinor <= 0) throw new SplitException(400, "Amount must be greater than 0");
    String status = approver(g, payee) == userId ? "APPROVED" : "PENDING";
    return repo.addSettlement(groupId, from, to, amountMinor, date == null ? LocalDate.now() : date, status, userId);
  }

  public void decide(long userId, long groupId, long settlementId, boolean approve) {
    Group g = visible(userId, groupId);
    Settlement s = repo.settlements(groupId).stream().filter(x -> x.id() == settlementId).findFirst()
        .orElseThrow(() -> new SplitException(404, "Settlement not found"));
    if (approver(g, member(groupId, s.toMember())) != userId) {
      throw new SplitException(403, "Only the person who was paid can approve this");
    }
    if (!repo.decideSettlement(groupId, settlementId, approve ? "APPROVED" : "REJECTED")) {
      throw new SplitException(409, "This settlement was already decided");
    }
  }

  public void deleteSettlement(long userId, long groupId, long settlementId) {
    Group g = visible(userId, groupId);
    Settlement s = repo.settlements(groupId).stream().filter(x -> x.id() == settlementId).findFirst()
        .orElseThrow(() -> new SplitException(404, "Settlement not found"));
    boolean creatorWhilePending = Objects.equals(s.createdBy(), userId) && "PENDING".equals(s.status());
    if (!creatorWhilePending && g.ownerId() != userId) {
      throw new SplitException(403, "Only the group owner can remove a decided settlement");
    }
    repo.deleteSettlement(groupId, settlementId);
  }

  /** The person paid approves; for someone without a login, the group owner approves on their behalf. */
  private static long approver(Group g, Member payee) {
    return payee != null && payee.userId() != null ? payee.userId() : g.ownerId();
  }

  /** Positive: the group owes this member; negative: they owe the group. Only approved settlements count. */
  private Map<Long, Long> net(long groupId) {
    Map<Long, Long> net = new HashMap<>();
    for (Expense e : repo.expenses(groupId)) net.merge(e.paidBy(), e.amountMinor(), Long::sum);
    for (Share s : repo.shares(groupId)) net.merge(s.memberId(), -s.amountMinor(), Long::sum);
    for (Settlement s : repo.settlements(groupId)) {
      if (!"APPROVED".equals(s.status())) continue;
      net.merge(s.fromMember(), s.amountMinor(), Long::sum);
      net.merge(s.toMember(), -s.amountMinor(), Long::sum);
    }
    return net;
  }

  /** Who pays whom to settle everything, largest debts first. */
  private static List<Debt> debts(Map<Long, Long> net) {
    List<long[]> owed = new ArrayList<>();
    List<long[]> owing = new ArrayList<>();
    net.forEach((member, amount) -> {
      if (amount > 0) owed.add(new long[] {member, amount});
      if (amount < 0) owing.add(new long[] {member, -amount});
    });
    Comparator<long[]> biggest = Comparator.comparingLong((long[] x) -> x[1]).reversed();
    owed.sort(biggest);
    owing.sort(biggest);
    List<Debt> debts = new ArrayList<>();
    int i = 0;
    int j = 0;
    while (i < owing.size() && j < owed.size()) {
      long pay = Math.min(owing.get(i)[1], owed.get(j)[1]);
      debts.add(new Debt(owing.get(i)[0], owed.get(j)[0], major(pay)));
      owing.get(i)[1] -= pay;
      owed.get(j)[1] -= pay;
      if (owing.get(i)[1] == 0) i++;
      if (owed.get(j)[1] == 0) j++;
    }
    return debts;
  }

  private Group visible(long userId, long groupId) {
    return repo.visibleGroup(userId, groupId).orElseThrow(() -> new SplitException(404, "Group not found"));
  }

  private Group owned(long userId, long groupId) {
    Group g = visible(userId, groupId);
    if (g.ownerId() != userId) throw new SplitException(403, "Only the group owner can change members or the group");
    return g;
  }

  private Member member(long groupId, long memberId) {
    return repo.members(groupId).stream().filter(m -> m.id() == memberId).findFirst()
        .orElseThrow(() -> new SplitException(400, "That person isn't in this group"));
  }

  private Long linkTarget(long groupId, String login, Long exceptMember) {
    if (login == null || login.isBlank()) return null;
    long userId = repo.userIdByLogin(login.trim())
        .orElseThrow(() -> new SplitException(400, "No login called " + login.trim()));
    Optional<Member> taken = repo.members(groupId).stream()
        .filter(m -> Objects.equals(m.userId(), userId) && !Objects.equals(m.id(), exceptMember))
        .findFirst();
    if (taken.isPresent()) throw new SplitException(409, login.trim() + " is already " + taken.get().name() + " in this group");
    return userId;
  }

  private static String clean(String value, String what) {
    String s = value == null ? "" : value.replaceAll("\\s+", " ").trim();
    if (s.isEmpty() || s.length() > MAX_NAME) throw new SplitException(400, what + " must be 1-" + MAX_NAME + " characters");
    return s;
  }

  private static double major(long minor) {
    return minor / 100.0;
  }
}
