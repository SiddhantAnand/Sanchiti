package com.sid.expense.repo;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {

  public record User(
      long id, String login, String name, String passwordHash, boolean hasPhoneKey, boolean admin,
      OffsetDateTime createdAt) {}

  private static final String COLUMNS =
      "u.id, u.login, u.display_name, u.password_hash, u.phone_key_hash IS NOT NULL AS has_phone_key, u.admin, u.created_at";

  private static final RowMapper<User> MAPPER = (rs, i) -> new User(
      rs.getLong("id"),
      rs.getString("login"),
      rs.getString("display_name"),
      rs.getString("password_hash"),
      rs.getBoolean("has_phone_key"),
      rs.getBoolean("admin"),
      rs.getObject("created_at", OffsetDateTime.class));

  private final JdbcClient jdbc;

  public UserRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public long create(String login, String name, String passwordHash, boolean admin) {
    return jdbc.sql("INSERT INTO users (login, display_name, password_hash, admin) VALUES (?, ?, ?, ?) RETURNING id")
        .params(login, name, passwordHash, admin)
        .query(Long.class)
        .single();
  }

  public Optional<User> findByLogin(String login) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM users u WHERE u.login = ?").param(login).query(MAPPER).optional();
  }

  public Optional<User> findByPhoneKeyHash(String hash) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM users u WHERE u.phone_key_hash = ?").param(hash).query(MAPPER).optional();
  }

  public Optional<User> findBySessionHash(String tokenHash) {
    return jdbc.sql("SELECT " + COLUMNS + """
         FROM sessions s JOIN users u ON u.id = s.user_id
        WHERE s.token_hash = ? AND s.expires_at > now()""")
        .param(tokenHash)
        .query(MAPPER)
        .optional();
  }

  public Optional<User> firstAdmin() {
    return jdbc.sql("SELECT " + COLUMNS + " FROM users u WHERE u.admin ORDER BY u.id LIMIT 1").query(MAPPER).optional();
  }

  public List<User> list() {
    return jdbc.sql("SELECT " + COLUMNS + " FROM users u ORDER BY u.id").query(MAPPER).list();
  }

  public List<Long> ids() {
    return jdbc.sql("SELECT id FROM users ORDER BY id").query(Long.class).list();
  }

  public void setUp(long id, String login, String name, String passwordHash) {
    jdbc.sql("UPDATE users SET login = ?, display_name = ?, password_hash = ? WHERE id = ?")
        .params(login, name, passwordHash, id)
        .update();
  }

  public void setPassword(long id, String passwordHash) {
    jdbc.sql("UPDATE users SET password_hash = ? WHERE id = ?").params(passwordHash, id).update();
  }

  public void setPhoneKey(long id, String key, String hash) {
    jdbc.sql("UPDATE users SET phone_key = ?, phone_key_hash = ? WHERE id = ?").params(key, hash, id).update();
  }

  /** Fills in the visible key for a hash stored before keys were kept (only if it really is that key). */
  public void backfillPhoneKey(long id, String key, String hash) {
    jdbc.sql("UPDATE users SET phone_key = ? WHERE id = ? AND phone_key IS NULL AND phone_key_hash = ?")
        .params(key, id, hash)
        .update();
  }

  public Optional<String> phoneKey(long id) {
    return jdbc.sql("SELECT phone_key FROM users WHERE id = ? AND phone_key IS NOT NULL")
        .param(id)
        .query(String.class)
        .optional();
  }

  public void createInvite(String codeHash, long createdBy, OffsetDateTime expiresAt) {
    jdbc.sql("DELETE FROM invites WHERE expires_at < now()").update();
    jdbc.sql("INSERT INTO invites (code_hash, created_by, expires_at) VALUES (?, ?, ?)")
        .params(codeHash, createdBy, expiresAt)
        .update();
  }

  /** Deletes the invite and reports whether it was valid; each code works once. */
  public boolean useInvite(String codeHash) {
    return jdbc.sql("DELETE FROM invites WHERE code_hash = ? AND expires_at > now()").param(codeHash).update() > 0;
  }
}
