package com.sid.expense.repo;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class AnalysisRepository {

  public record StoredAnalysis(
      int id,
      LocalDate periodFrom,
      LocalDate periodTo,
      String model,
      String inputHash,
      Object stats,
      Object insights,
      Object usage,
      OffsetDateTime createdAt) {}

  private static final String COLUMNS =
      "id, period_from, period_to, model, input_hash, stats, insights, token_usage, created_at";

  private final JdbcClient jdbc;
  private final JsonMapper json;
  private final RowMapper<StoredAnalysis> mapper;

  public AnalysisRepository(JdbcClient jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
    this.mapper = (rs, i) -> new StoredAnalysis(
        rs.getInt("id"),
        rs.getObject("period_from", LocalDate.class),
        rs.getObject("period_to", LocalDate.class),
        rs.getString("model"),
        rs.getString("input_hash"),
        parse(rs.getString("stats")),
        parse(rs.getString("insights")),
        parse(rs.getString("token_usage")),
        rs.getObject("created_at", OffsetDateTime.class));
  }

  public StoredAnalysis save(
      long userId, LocalDate from, LocalDate to, String model, String inputHash, Object stats, Object insights,
      Object usage) {
    return jdbc.sql("""
        INSERT INTO analyses (user_id, period_from, period_to, model, input_hash, stats, insights, token_usage)
        VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), CAST(? AS jsonb))
        RETURNING """ + " " + COLUMNS)
        .params(userId, from, to, model, inputHash, json.writeValueAsString(stats), json.writeValueAsString(insights),
            usage == null ? null : json.writeValueAsString(usage))
        .query(mapper)
        .single();
  }

  public Optional<StoredAnalysis> latest(long userId) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM analyses WHERE user_id = ? ORDER BY id DESC LIMIT 1")
        .param(userId)
        .query(mapper)
        .optional();
  }

  public List<StoredAnalysis> list(long userId, int limit) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM analyses WHERE user_id = ? ORDER BY id DESC LIMIT ?")
        .params(userId, limit)
        .query(mapper)
        .list();
  }

  private Object parse(String value) {
    return value == null ? null : json.readValue(value, Object.class);
  }
}
