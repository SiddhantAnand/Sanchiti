package com.sid.expense.repo;

import com.sid.expense.domain.Direction;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Categories and banks a user added on top of the built-in ones. */
@Repository
public class UserListsRepository {

  public record CustomCategory(String name, Direction direction) {}

  private final JdbcClient jdbc;

  public UserListsRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public List<CustomCategory> categories(long userId) {
    return jdbc.sql("SELECT name, direction FROM user_categories WHERE user_id = ? ORDER BY lower(name)")
        .param(userId)
        .query((rs, i) -> new CustomCategory(rs.getString("name"), Direction.valueOf(rs.getString("direction"))))
        .list();
  }

  public void addCategory(long userId, String name, Direction direction) {
    jdbc.sql("""
        INSERT INTO user_categories (user_id, name, direction) VALUES (?, ?, ?)
        ON CONFLICT (user_id, direction, lower(name)) DO NOTHING""")
        .params(userId, name, direction.name())
        .update();
  }

  public List<String> banks(long userId) {
    return jdbc.sql("SELECT name FROM user_banks WHERE user_id = ? ORDER BY lower(name)")
        .param(userId)
        .query(String.class)
        .list();
  }

  public void addBank(long userId, String name) {
    jdbc.sql("INSERT INTO user_banks (user_id, name) VALUES (?, ?) ON CONFLICT (user_id, lower(name)) DO NOTHING")
        .params(userId, name)
        .update();
  }
}
