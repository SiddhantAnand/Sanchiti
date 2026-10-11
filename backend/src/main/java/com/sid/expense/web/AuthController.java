package com.sid.expense.web;

import com.sid.expense.config.AppProperties;
import com.sid.expense.repo.SessionRepository;
import com.sid.expense.repo.UserRepository;
import com.sid.expense.repo.UserRepository.User;
import com.sid.expense.service.TransactionService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Web sign-in with a login ID and password; new accounts need an invite code from an admin. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

  private static final Duration SESSION_TTL = Duration.ofDays(30);
  private static final int MAX_FAILURES = 5;
  private static final Duration LOCKOUT = Duration.ofMinutes(5);
  private static final int MAX_TRACKED_LOGINS = 10_000;

  record Me(String login, String name, boolean admin, boolean hasPhoneKey) {}

  private record Failures(int count, Instant lockedUntil) {}

  private final AccountService accounts;
  private final UserRepository users;
  private final SessionRepository sessions;
  private final boolean secureCookies;
  private final Map<String, Failures> failures = new ConcurrentHashMap<>();

  public AuthController(
      AccountService accounts, UserRepository users, SessionRepository sessions, AppProperties props) {
    this.accounts = accounts;
    this.users = users;
    this.sessions = sessions;
    this.secureCookies = props.secureCookies();
  }

  @GetMapping("/me")
  public Me me(@RequestAttribute(AuthUser.ATTRIBUTE) AuthUser user) {
    return users.findByLogin(user.login()).map(AuthController::me).orElseThrow();
  }

  @PostMapping("/login")
  public ResponseEntity<Me> login(@RequestBody Map<String, Object> body, HttpServletRequest req) {
    String login = AccountService.normalizeLogin(text(body.get("login")));
    Failures f = failures.get(login);
    if (f != null && Instant.now().isBefore(f.lockedUntil())) {
      throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many attempts. Try again in a few minutes.");
    }
    User user = accounts.authenticate(login, text(body.get("password"))).orElseGet(() -> {
      recordFailure(login);
      throw new ApiException(HttpStatus.UNAUTHORIZED, "Login ID or password is incorrect");
    });
    failures.remove(login);
    return signedIn(user, req);
  }

  @PostMapping("/signup")
  public ResponseEntity<Me> signUp(@RequestBody Map<String, Object> body, HttpServletRequest req) {
    String login = text(body.get("login"));
    accounts.signUp(text(body.get("invite")), login, text(body.get("name")), text(body.get("password")));
    return signedIn(users.findByLogin(AccountService.normalizeLogin(login)).orElseThrow(), req);
  }

  @PostMapping("/logout")
  public ResponseEntity<Void> logout(HttpServletRequest req) {
    ApiGuardFilter.sessionToken(req).ifPresent(t -> sessions.delete(TransactionService.sha256(t)));
    return ResponseEntity.noContent()
        .header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO, req).toString())
        .build();
  }

  static Me me(User u) {
    return new Me(u.login(), u.name(), u.admin(), u.hasPhoneKey());
  }

  private ResponseEntity<Me> signedIn(User user, HttpServletRequest req) {
    String token = accounts.randomToken();
    sessions.create(TransactionService.sha256(token), user.id(), OffsetDateTime.now().plus(SESSION_TTL));
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, cookie(token, SESSION_TTL, req).toString())
        .body(me(user));
  }

  private void recordFailure(String login) {
    if (failures.size() > MAX_TRACKED_LOGINS) failures.clear();
    failures.compute(login, (k, old) -> {
      int count = (old == null ? 0 : old.count()) + 1;
      return count >= MAX_FAILURES ? new Failures(0, Instant.now().plus(LOCKOUT)) : new Failures(count, Instant.EPOCH);
    });
  }

  private static String text(Object value) {
    return value instanceof String s ? s : null;
  }

  private ResponseCookie cookie(String value, Duration maxAge, HttpServletRequest req) {
    return ResponseCookie.from(ApiGuardFilter.SESSION_COOKIE, value)
        .httpOnly(true)
        .secure(secureCookies || req.isSecure())
        .sameSite("Strict")
        .path("/")
        .maxAge(maxAge)
        .build();
  }
}
