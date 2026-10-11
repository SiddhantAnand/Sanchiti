package com.sid.expense.repo;

import java.time.OffsetDateTime;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Web sign-in sessions; only a SHA-256 of the cookie token is stored. */
@Repository
public class SessionRepository {

  private final JdbcClient jdbc;

  public SessionRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void create(String tokenHash, long userId, OffsetDateTime expiresAt) {
    jdbc.sql("DELETE FROM sessions WHERE expires_at < now()").update();
    jdbc.sql("INSERT INTO sessions (token_hash, user_id, expires_at) VALUES (?, ?, ?)")
        .params(tokenHash, userId, expiresAt)
        .update();
  }

  public void delete(String tokenHash) {
    jdbc.sql("DELETE FROM sessions WHERE token_hash = ?").param(tokenHash).update();
  }

  /** Signs the user out everywhere except the given session. */
  public void deleteOthers(long userId, String keepTokenHash) {
    jdbc.sql("DELETE FROM sessions WHERE user_id = ? AND token_hash <> ?").params(userId, keepTokenHash).update();
  }
}
