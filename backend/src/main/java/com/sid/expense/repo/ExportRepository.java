package com.sid.expense.repo;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Every row a user owns, as JSON straight from Postgres, so new columns are exported without code changes. */
@Repository
public class ExportRepository {

  /** Table -> sort order. Fixed list: these names are concatenated into SQL. */
  private static final Map<String, String> USER_TABLES = new LinkedHashMap<>();

  static {
    USER_TABLES.put("transactions", "date, created_at");
    USER_TABLES.put("reason_categories", "reason_key");
    USER_TABLES.put("review", "id");
    USER_TABLES.put("analyses", "id");
    USER_TABLES.put("user_categories", "id");
    USER_TABLES.put("user_banks", "id");
  }

  private final JdbcClient jdbc;

  public ExportRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Table name -> JSON array text of that user's rows (without the user_id column). */
  public Map<String, String> userTables(long userId) {
    Map<String, String> out = new LinkedHashMap<>();
    USER_TABLES.forEach((table, order) -> out.put(table, jdbc.sql(
            "SELECT COALESCE(json_agg(to_jsonb(t) - 'user_id' ORDER BY " + order + "), '[]')::text FROM "
                + table + " t WHERE user_id = ?")
        .param(userId)
        .query(String.class)
        .single()));
    return out;
  }

  public String schemaVersion() {
    return jdbc.sql("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1")
        .query(String.class)
        .optional()
        .orElse("unknown");
  }
}
