package com.sid.expense.web;

import com.sid.expense.config.AppProperties;
import com.sid.expense.repo.UserRepository;
import com.sid.expense.repo.UserRepository.User;
import com.sid.expense.service.TransactionService;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Accounts: password checks, invite-only sign-up, phone keys, and the owner account created from .env. */
@Service
public class AccountService {

  private static final Logger log = LoggerFactory.getLogger(AccountService.class);
  private static final Pattern LOGIN = Pattern.compile("[a-z0-9][a-z0-9._-]{2,31}");
  private static final int MIN_PASSWORD = 10;
  // BCrypt only reads the first 72 bytes.
  private static final int MAX_PASSWORD = 64;
  private static final Duration INVITE_TTL = Duration.ofDays(7);

  public record Invite(String code, OffsetDateTime expiresAt) {}

  private final UserRepository users;
  private final AppProperties props;
  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
  private final SecureRandom random = new SecureRandom();
  // Checked when the login ID is unknown so a wrong ID takes as long as a wrong password.
  private final String dummyHash = encoder.encode("not-a-real-password");

  public AccountService(UserRepository users, AppProperties props) {
    this.users = users;
    this.props = props;
  }

  /** Creates the owner from APP_LOGIN/APP_USER/APP_PASSWORD, or finishes the one the V4 migration made for old data. */
  @EventListener(ApplicationStartedEvent.class)
  @Transactional
  public void ensureOwner() {
    var owner = props.owner();
    Optional<User> existing = users.firstAdmin();
    boolean needsPassword = existing.map(u -> u.passwordHash().isEmpty()).orElse(true);
    if (needsPassword) {
      if (owner.password() == null || owner.password().isBlank()) {
        log.warn("No account can sign in yet: set APP_LOGIN, APP_USER and APP_PASSWORD in .env and restart.");
      } else {
        String login = validLogin(owner.login());
        String name = validName(owner.name());
        String hash = encoder.encode(validPassword(owner.password()));
        if (existing.isPresent()) users.setUp(existing.get().id(), login, name, hash);
        else users.create(login, name, hash, true);
        log.info("Owner account '{}' is ready.", login);
      }
    }
    Optional<User> admin = users.firstAdmin();
    if (admin.isPresent() && owner.phoneKey() != null && !owner.phoneKey().isBlank()) {
      String key = owner.phoneKey().trim();
      String hash = TransactionService.sha256(key);
      if (admin.get().hasPhoneKey()) users.backfillPhoneKey(admin.get().id(), key, hash);
      else users.setPhoneKey(admin.get().id(), key, hash);
    }
  }

  public Optional<User> authenticate(String login, String password) {
    Optional<User> user = users.findByLogin(normalizeLogin(login)).filter(u -> !u.passwordHash().isEmpty());
    boolean ok = encoder.matches(password == null ? "" : password, user.map(User::passwordHash).orElse(dummyHash));
    return ok ? user : Optional.empty();
  }

  @Transactional
  public void signUp(String inviteCode, String login, String name, String password) {
    String l = validLogin(login);
    String n = validName(name);
    String hash = encoder.encode(validPassword(password));
    if (inviteCode == null || !users.useInvite(TransactionService.sha256(inviteCode.trim()))) {
      throw new ApiException(HttpStatus.FORBIDDEN, "Invite code is invalid, used or expired");
    }
    try {
      users.create(l, n, hash, false);
    } catch (DuplicateKeyException e) {
      throw new ApiException(HttpStatus.CONFLICT, "That login ID is taken");
    }
  }

  public void changePassword(User user, String current, String next) {
    if (authenticate(user.login(), current).isEmpty()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "Current password is incorrect");
    }
    users.setPassword(user.id(), encoder.encode(validPassword(next)));
  }

  /** Returns the new key; the old key stops working. */
  public String newPhoneKey(long userId) {
    String key = randomToken();
    users.setPhoneKey(userId, key, TransactionService.sha256(key));
    return key;
  }

  public Invite newInvite(long adminId) {
    String code = randomToken();
    OffsetDateTime expiresAt = OffsetDateTime.now().plus(INVITE_TTL);
    users.createInvite(TransactionService.sha256(code), adminId, expiresAt);
    return new Invite(code, expiresAt);
  }

  public String randomToken() {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  static String normalizeLogin(String login) {
    return login == null ? "" : login.trim().toLowerCase(Locale.ROOT);
  }

  private static String validLogin(String login) {
    String l = normalizeLogin(login);
    if (!LOGIN.matcher(l).matches()) {
      throw new ApiException(HttpStatus.BAD_REQUEST,
          "Login ID must be 3-32 characters: letters, digits, '.', '_' or '-', starting with a letter or digit");
    }
    return l;
  }

  private static String validName(String name) {
    String n = name == null ? "" : name.trim().replaceAll("\\s+", " ");
    if (n.isEmpty() || n.length() > 60 || n.chars().anyMatch(Character::isISOControl)) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "Name must be 1-60 characters");
    }
    return n;
  }

  private static String validPassword(String password) {
    if (password == null || password.length() < MIN_PASSWORD || password.length() > MAX_PASSWORD) {
      throw new ApiException(HttpStatus.BAD_REQUEST,
          "Password must be " + MIN_PASSWORD + "-" + MAX_PASSWORD + " characters");
    }
    return password;
  }
}
