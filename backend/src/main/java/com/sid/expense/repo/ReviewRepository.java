package com.sid.expense.repo;

import com.sid.expense.service.TransactionService;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class ReviewRepository {

  public record ReviewItem(long id, Object payload, String issue, OffsetDateTime receivedAt) {}

  /** An incoming record that could not be stored as a transaction, and why. */
  public record Pending(Object payload, String issue) {}

  private final JdbcClient jdbc;
  private final JsonMapper json;
  private final RowMapper<ReviewItem> mapper;

  public ReviewRepository(JdbcClient jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
    this.mapper = (rs, i) -> new ReviewItem(
        rs.getLong("id"),
        json.readValue(rs.getString("payload"), Object.class),
        rs.getString("issue"),
        rs.getObject("received_at", OffsetDateTime.class));
  }

  /** Returns false when the identical record is already waiting for review. */
  public boolean save(long userId, Object payload, String issue) {
    String body = json.writeValueAsString(payload);
    return jdbc.sql("""
        INSERT INTO review (user_id, payload, issue, payload_hash) VALUES (?, CAST(? AS jsonb), ?, ?)
        ON CONFLICT (user_id, payload_hash) DO NOTHING""")
        .params(userId, body, issue, TransactionService.sha256(body))
        .update() > 0;
  }

  public List<ReviewItem> list(long userId, int limit) {
    return jdbc.sql("SELECT id, payload, issue, received_at FROM review WHERE user_id = ? ORDER BY id DESC LIMIT ?")
        .params(userId, limit)
        .query(mapper)
        .list();
  }

  public boolean exists(long userId, long id) {
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM review WHERE user_id = ? AND id = ?)")
        .params(userId, id)
        .query(Boolean.class)
        .single();
  }

  public boolean delete(long userId, long id) {
    return jdbc.sql("DELETE FROM review WHERE user_id = ? AND id = ?").params(userId, id).update() > 0;
  }
}
