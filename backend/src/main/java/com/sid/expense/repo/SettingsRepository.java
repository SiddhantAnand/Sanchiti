package com.sid.expense.repo;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SettingsRepository {

  private final JdbcClient jdbc;

  public SettingsRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<String> get(String key) {
    return jdbc.sql("SELECT value FROM settings WHERE key = ?").param(key).query(String.class).optional();
  }

  public void put(String key, String value) {
    jdbc.sql("""
        INSERT INTO settings (key, value) VALUES (?, ?)
        ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()""")
        .params(key, value)
        .update();
  }

  public void delete(String key) {
    jdbc.sql("DELETE FROM settings WHERE key = ?").param(key).update();
  }
}
