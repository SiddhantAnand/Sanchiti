package com.sid.expense.web;

import com.sid.expense.repo.UserRepository.User;

/** The signed-in account, put on the request by {@link ApiGuardFilter}; read with {@code @RequestAttribute(AuthUser.ATTRIBUTE)}. */
public record AuthUser(long id, String login, String name, boolean admin) {

  public static final String ATTRIBUTE = "authUser";

  static AuthUser of(User u) {
    return new AuthUser(u.id(), u.login(), u.name(), u.admin());
  }
}
