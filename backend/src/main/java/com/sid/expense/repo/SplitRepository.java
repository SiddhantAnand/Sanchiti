package com.sid.expense.repo;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Split groups, their members, shared expenses and settlements. Access rules live in SplitService. */
@Repository
public class SplitRepository {

  public record Group(long id, long ownerId, String ownerName, String name, OffsetDateTime createdAt) {}

  public record Member(long id, long groupId, String name, Long userId, String login) {}

  public record Expense(
      long id, String description, long amountMinor, LocalDate date, long paidBy, UUID transactionId, Long createdBy,
      boolean deleteRequested, Long deleteRequestedBy) {}

  public record Share(long expenseId, long memberId, long amountMinor) {}

  public record Settlement(
      long id, long fromMember, long toMember, long amountMinor, LocalDate date, String status, Long createdBy) {}

  public record Person(String login, String name) {}

  private final JdbcClient jdbc;

  public SplitRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  private static final String GROUP_COLUMNS =
      "g.id, g.owner_id, u.display_name AS owner_name, g.name, g.created_at FROM split_groups g JOIN users u ON u.id = g.owner_id";

  private static Group group(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new Group(rs.getLong("id"), rs.getLong("owner_id"), rs.getString("owner_name"), rs.getString("name"),
        rs.getObject("created_at", OffsetDateTime.class));
  }

  /** Groups the user owns or is a linked member of. */
  public List<Group> groupsFor(long userId) {
    return jdbc.sql("SELECT " + GROUP_COLUMNS + " " + """
         WHERE g.owner_id = :u OR EXISTS (SELECT 1 FROM split_members m WHERE m.group_id = g.id AND m.user_id = :u)
        ORDER BY g.created_at DESC""")
        .param("u", userId)
        .query((rs, i) -> group(rs))
        .list();
  }

  public Optional<Group> visibleGroup(long userId, long groupId) {
    return jdbc.sql("SELECT " + GROUP_COLUMNS + " " + """
         WHERE g.id = :g AND (g.owner_id = :u
           OR EXISTS (SELECT 1 FROM split_members m WHERE m.group_id = g.id AND m.user_id = :u))""")
        .param("u", userId)
        .param("g", groupId)
        .query((rs, i) -> group(rs))
        .optional();
  }

  public long createGroup(long ownerId, String name) {
    return jdbc.sql("INSERT INTO split_groups (owner_id, name) VALUES (?, ?) RETURNING id")
        .params(ownerId, name)
        .query(Long.class)
        .single();
  }

  public void renameGroup(long groupId, String name) {
    jdbc.sql("UPDATE split_groups SET name = ? WHERE id = ?").params(name, groupId).update();
  }

  public void deleteGroup(long groupId) {
    jdbc.sql("DELETE FROM split_groups WHERE id = ?").param(groupId).update();
  }

  public List<Member> members(long groupId) {
    return jdbc.sql("""
        SELECT m.id, m.group_id, m.name, m.user_id, u.login FROM split_members m
        LEFT JOIN users u ON u.id = m.user_id WHERE m.group_id = ? ORDER BY m.id""")
        .param(groupId)
        .query((rs, i) -> new Member(rs.getLong("id"), rs.getLong("group_id"), rs.getString("name"),
            rs.getObject("user_id", Long.class), rs.getString("login")))
        .list();
  }

  public long addMember(long groupId, String name, Long userId) {
    return jdbc.sql("INSERT INTO split_members (group_id, name, user_id) VALUES (?, ?, ?) RETURNING id")
        .params(groupId, name, userId)
        .query(Long.class)
        .single();
  }

  public void updateMember(long memberId, String name, Long userId) {
    jdbc.sql("UPDATE split_members SET name = ?, user_id = ? WHERE id = ?").params(name, userId, memberId).update();
  }

  public boolean memberInUse(long memberId) {
    return jdbc.sql("""
        SELECT EXISTS (SELECT 1 FROM split_expenses WHERE paid_by = :m)
            OR EXISTS (SELECT 1 FROM split_shares WHERE member_id = :m AND amount_minor > 0)
            OR EXISTS (SELECT 1 FROM split_settlements WHERE from_member = :m OR to_member = :m)""")
        .param("m", memberId)
        .query(Boolean.class)
        .single();
  }

  public void deleteMember(long memberId) {
    jdbc.sql("DELETE FROM split_members WHERE id = ?").param(memberId).update();
  }

  public List<Expense> expenses(long groupId) {
    return jdbc.sql("""
        SELECT id, description, amount_minor, date, paid_by, transaction_id, created_by,
          delete_requested_at IS NOT NULL AS delete_requested, delete_requested_by FROM split_expenses
        WHERE group_id = ? ORDER BY date DESC, id DESC""")
        .param(groupId)
        .query((rs, i) -> new Expense(rs.getLong("id"), rs.getString("description"), rs.getLong("amount_minor"),
            rs.getObject("date", LocalDate.class), rs.getLong("paid_by"), rs.getObject("transaction_id", UUID.class),
            rs.getObject("created_by", Long.class), rs.getBoolean("delete_requested"),
            rs.getObject("delete_requested_by", Long.class)))
        .list();
  }

  public List<Share> shares(long groupId) {
    return jdbc.sql("""
        SELECT s.expense_id, s.member_id, s.amount_minor FROM split_shares s
        JOIN split_expenses e ON e.id = s.expense_id WHERE e.group_id = ?""")
        .param(groupId)
        .query((rs, i) -> new Share(rs.getLong("expense_id"), rs.getLong("member_id"), rs.getLong("amount_minor")))
        .list();
  }

  public long addExpense(
      long groupId, String description, long amountMinor, LocalDate date, long paidBy, UUID transactionId,
      long createdBy) {
    return jdbc.sql("""
        INSERT INTO split_expenses (group_id, description, amount_minor, date, paid_by, transaction_id, created_by)
        VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id""")
        .params(groupId, description, amountMinor, date, paidBy, transactionId, createdBy)
        .query(Long.class)
        .single();
  }

  public void addShare(long expenseId, long memberId, long amountMinor) {
    jdbc.sql("INSERT INTO split_shares (expense_id, member_id, amount_minor) VALUES (?, ?, ?)")
        .params(expenseId, memberId, amountMinor)
        .update();
  }

  public void updateExpense(
      long expenseId, String description, long amountMinor, LocalDate date, long paidBy, UUID transactionId) {
    jdbc.sql("""
        UPDATE split_expenses SET description = ?, amount_minor = ?, date = ?, paid_by = ?, transaction_id = ?
        WHERE id = ?""")
        .params(description, amountMinor, date, paidBy, transactionId, expenseId)
        .update();
  }

  public void deleteShares(long expenseId) {
    jdbc.sql("DELETE FROM split_shares WHERE expense_id = ?").param(expenseId).update();
  }

  public boolean deleteExpense(long groupId, long expenseId) {
    return jdbc.sql("DELETE FROM split_expenses WHERE group_id = ? AND id = ?").params(groupId, expenseId).update() > 0;
  }

  /** False when a request is already waiting. */
  public boolean requestDelete(long groupId, long expenseId, long userId) {
    return jdbc.sql("""
        UPDATE split_expenses SET delete_requested_by = ?, delete_requested_at = now()
        WHERE group_id = ? AND id = ? AND delete_requested_at IS NULL""")
        .params(userId, groupId, expenseId)
        .update() > 0;
  }

  public void clearDeleteRequest(long groupId, long expenseId) {
    jdbc.sql("UPDATE split_expenses SET delete_requested_by = NULL, delete_requested_at = NULL WHERE group_id = ? AND id = ?")
        .params(groupId, expenseId)
        .update();
  }

  public List<Settlement> settlements(long groupId) {
    return jdbc.sql("""
        SELECT id, from_member, to_member, amount_minor, date, status, created_by FROM split_settlements
        WHERE group_id = ? ORDER BY date DESC, id DESC""")
        .param(groupId)
        .query((rs, i) -> new Settlement(rs.getLong("id"), rs.getLong("from_member"), rs.getLong("to_member"),
            rs.getLong("amount_minor"), rs.getObject("date", LocalDate.class), rs.getString("status"),
            rs.getObject("created_by", Long.class)))
        .list();
  }

  public long addSettlement(
      long groupId, long from, long to, long amountMinor, LocalDate date, String status, long createdBy) {
    return jdbc.sql("""
        INSERT INTO split_settlements (group_id, from_member, to_member, amount_minor, date, status, created_by,
          decided_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, CASE WHEN CAST(? AS TEXT) = 'PENDING' THEN NULL ELSE now() END) RETURNING id""")
        .params(groupId, from, to, amountMinor, date, status, createdBy, status)
        .query(Long.class)
        .single();
  }

  public boolean decideSettlement(long groupId, long settlementId, String status) {
    return jdbc.sql("""
        UPDATE split_settlements SET status = ?, decided_at = now()
        WHERE group_id = ? AND id = ? AND status = 'PENDING'""")
        .params(status, groupId, settlementId)
        .update() > 0;
  }

  public boolean deleteSettlement(long groupId, long settlementId) {
    return jdbc.sql("DELETE FROM split_settlements WHERE group_id = ? AND id = ?")
        .params(groupId, settlementId)
        .update() > 0;
  }

  /** The user's own transactions that one of their split expenses points at. */
  public List<UUID> linkedTransactions(long userId) {
    return jdbc.sql("""
        SELECT DISTINCT e.transaction_id FROM split_expenses e JOIN transactions t ON t.id = e.transaction_id
        WHERE e.created_by = ? AND t.user_id = e.created_by""")
        .param(userId)
        .query((rs, i) -> rs.getObject(1, UUID.class))
        .list();
  }

  /** Everyone with a login, for linking members (name and login only). */
  public List<Person> people() {
    return jdbc.sql("SELECT login, display_name AS name FROM users ORDER BY lower(display_name)")
        .query((rs, i) -> new Person(rs.getString("login"), rs.getString("name")))
        .list();
  }

  public Optional<Long> userIdByLogin(String login) {
    return jdbc.sql("SELECT id FROM users WHERE lower(login) = lower(?)").param(login).query(Long.class).optional();
  }
}
