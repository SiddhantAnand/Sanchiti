package com.sid.expense.web;

import com.sid.expense.ai.AiException;
import com.sid.expense.ai.AiSettings;
import com.sid.expense.ai.GeminiClient;
import com.sid.expense.repo.ExportRepository;
import com.sid.expense.repo.SessionRepository;
import com.sid.expense.repo.UserRepository;
import com.sid.expense.service.TransactionService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Settings for the signed-in account, plus invites and the user list for admins. */
@RestController
@RequestMapping("/api/account")
public class AccountController {

  record PhoneKey(String phoneKey) {}

  record UserRow(String login, String name, boolean admin, OffsetDateTime createdAt) {}

  record UserList(List<UserRow> users) {}

  /** The key itself is never sent back; {@code keyHint} shows its last 4 characters. */
  record AiStatus(boolean enabled, String source, String keyHint, String model) {}

  // Google issues both "AIza…" keys and newer "AQ.…" ones; Google itself is the real check (see saveAi).
  private static final Pattern API_KEY = Pattern.compile("[A-Za-z0-9._-]{20,200}");
  private static final Pattern MODEL = Pattern.compile("[a-z0-9][a-z0-9.-]{2,63}");

  private final AccountService accounts;
  private final UserRepository users;
  private final SessionRepository sessions;
  private final AiSettings ai;
  private final GeminiClient gemini;
  private final ExportRepository exports;
  private final JsonMapper json;

  public AccountController(
      AccountService accounts, UserRepository users, SessionRepository sessions, AiSettings ai, GeminiClient gemini,
      ExportRepository exports, JsonMapper json) {
    this.accounts = accounts;
    this.users = users;
    this.sessions = sessions;
    this.ai = ai;
    this.gemini = gemini;
    this.exports = exports;
    this.json = json;
  }

  @GetMapping("/phone-key")
  public PhoneKey phoneKey(@RequestAttribute(AuthUser.ATTRIBUTE) AuthUser user) {
    return new PhoneKey(users.phoneKey(user.id()).orElse(null));
  }

  /** A backup of everything this account owns; passwords, keys and sessions are left out. */
  @GetMapping("/export")
  public ResponseEntity<Map<String, Object>> export(@RequestAttribute(AuthUser.ATTRIBUTE) AuthUser user) {
    var account = users.findByLogin(user.login()).orElseThrow();
    Map<String, Object> data = new LinkedHashMap<>();
    Map<String, Integer> counts = new LinkedHashMap<>();
    exports.userTables(user.id()).forEach((table, rows) -> {
      JsonNode node = json.readTree(rows);
      data.put(table, node);
      counts.put(table, node.size());
    });
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("app", "Sanchiti");
    body.put("format", 1);
    body.put("schemaVersion", exports.schemaVersion());
    body.put("exportedAt", OffsetDateTime.now());
    body.put("account", Map.of("login", account.login(), "name", account.name(), "createdAt", account.createdAt()));
    body.put("counts", counts);
    body.put("data", data);
    String file = "sanchiti-" + account.login() + "-" + LocalDate.now() + ".json";
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file + "\"")
        .body(body);
  }

  @PostMapping("/phone-key")
  public PhoneKey newPhoneKey(@RequestAttribute(AuthUser.ATTRIBUTE) AuthUser user) {
    return new PhoneKey(accounts.newPhoneKey(user.id()));
  }

  /** Also signs the account out of every other browser. */
  @PostMapping("/password")
  public ResponseEntity<Void> changePassword(
      @RequestAttribute(AuthUser.ATTRIBUTE) AuthUser user, @RequestBody Map<String, Object> body,
      HttpServletRequest req) {
    accounts.changePassword(users.findByLogin(user.login()).orElseThrow(),
        body.get("current") instanceof String s ? s : null, body.get("next") instanceof String s ? s : null);
    ApiGuardFilter.sessionToken(req).ifPresent(t -> sessions.deleteOthers(user.id(), TransactionService.sha256(t)));
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/invites")
  public ResponseEntity<AccountService.Invite> invite(@RequestAttribute(AuthUser.ATTRIBUTE) AuthUser user) {
    requireAdmin(user);
    return ResponseEntity.status(HttpStatus.CREATED).body(accounts.newInvite(user.id()));
  }

  @GetMapping("/users")
  public UserList users(@RequestAttribute(AuthUser.ATTRIBUTE) AuthUser user) {
    requireAdmin(user);
    return new UserList(users.list().stream()
        .map(u -> new UserRow(u.login(), u.name(), u.admin(), u.createdAt()))
        .toList());
  }

  @GetMapping("/ai")
  public AiStatus aiStatus(@RequestAttribute(AuthUser.ATTRIBUTE) AuthUser user) {
    requireAdmin(user);
    return aiStatus();
  }

  /** Checks the key with Google first, so a typo never replaces a working key. */
  @PutMapping("/ai")
  public AiStatus saveAi(@RequestAttribute(AuthUser.ATTRIBUTE) AuthUser user, @RequestBody Map<String, Object> body) {
    requireAdmin(user);
    String key = body.get("apiKey") instanceof String s ? s.trim() : "";
    String model = body.get("model") instanceof String s && !s.isBlank() ? s.trim() : ai.defaultModel();
    if (!API_KEY.matcher(key).matches()) throw new ApiException(HttpStatus.BAD_REQUEST, "That doesn't look like a Gemini API key");
    if (!MODEL.matcher(model).matches()) throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid model name");
    try {
      gemini.verify(key, model);
    } catch (AiException e) {
      throw new ApiException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
    ai.save(key, model);
    return aiStatus();
  }

  /** Removes the saved key; GEMINI_API_KEY from the environment is used again if set. */
  @DeleteMapping("/ai")
  public AiStatus clearAi(@RequestAttribute(AuthUser.ATTRIBUTE) AuthUser user) {
    requireAdmin(user);
    ai.clear();
    return aiStatus();
  }

  private AiStatus aiStatus() {
    var c = ai.current();
    String hint = c.apiKey() == null ? null : "…" + c.apiKey().substring(Math.max(0, c.apiKey().length() - 4));
    return new AiStatus(c.enabled(), c.source().name().toLowerCase(Locale.ROOT), hint, c.model());
  }

  private static void requireAdmin(AuthUser user) {
    if (!user.admin()) throw new ApiException(HttpStatus.FORBIDDEN, "Only an admin can do this");
  }
}
