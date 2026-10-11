package com.sid.expense.web;

import com.sid.expense.repo.UserRepository;
import com.sid.expense.repo.UserRepository.User;
import com.sid.expense.service.TransactionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Security headers on every response; 1 MB body cap on /api/**. Every /api/** call (except sign-in) needs a web
 * session cookie, or a user's phone key as a Bearer token; a phone key may only post SMS.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiGuardFilter extends OncePerRequestFilter {

  static final long MAX_BODY_BYTES = 1024 * 1024;
  static final String SESSION_COOKIE = "session";
  /** Cookie-authenticated writes must carry this header; a cross-site form or image can't set it. */
  static final String CSRF_HEADER = "X-Requested-With";
  static final String SMS_PATH = "/api/transactions/sms";

  private static final Set<String> PUBLIC = Set.of("/api/auth/login", "/api/auth/logout", "/api/auth/signup");
  private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");
  private static final String CSP = String.join("; ",
      "default-src 'self'", "script-src 'self'", "style-src 'self' 'unsafe-inline'", "img-src 'self' data:",
      "font-src 'self' data:", "connect-src 'self'", "object-src 'none'", "base-uri 'self'",
      "form-action 'self'", "frame-ancestors 'none'");

  private final UserRepository users;

  public ApiGuardFilter(UserRepository users) {
    this.users = users;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    res.setHeader("X-Content-Type-Options", "nosniff");
    res.setHeader("X-Frame-Options", "DENY");
    res.setHeader("Referrer-Policy", "no-referrer");
    res.setHeader("Content-Security-Policy", CSP);

    String uri = req.getRequestURI();
    if (uri.startsWith("/api/")) {
      res.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
      if (req.getContentLengthLong() > MAX_BODY_BYTES) {
        write(res, 413, "Payload too large (max 1 MB)");
        return;
      }
      if (!PUBLIC.contains(uri)) {
        String auth = req.getHeader(HttpHeaders.AUTHORIZATION);
        Optional<User> user;
        if (auth != null && auth.startsWith("Bearer ")) {
          if (!("POST".equals(req.getMethod()) && SMS_PATH.equals(uri))) {
            write(res, HttpServletResponse.SC_FORBIDDEN, "A phone key can only send SMS");
            return;
          }
          // Keys are 256-bit random, so an indexed lookup of their SHA-256 leaks nothing useful through timing.
          user = users.findByPhoneKeyHash(TransactionService.sha256(auth.substring(7).trim()));
        } else {
          user = sessionToken(req).flatMap(t -> users.findBySessionHash(TransactionService.sha256(t)));
          if (user.isPresent() && !SAFE_METHODS.contains(req.getMethod()) && req.getHeader(CSRF_HEADER) == null) {
            write(res, HttpServletResponse.SC_FORBIDDEN, "Missing " + CSRF_HEADER + " header");
            return;
          }
        }
        if (user.isEmpty()) {
          res.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
          write(res, HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized");
          return;
        }
        req.setAttribute(AuthUser.ATTRIBUTE, AuthUser.of(user.get()));
      }
    }
    chain.doFilter(req, res);
  }

  static Optional<String> sessionToken(HttpServletRequest req) {
    if (req.getCookies() == null) return Optional.empty();
    for (Cookie c : req.getCookies()) {
      if (SESSION_COOKIE.equals(c.getName()) && !c.getValue().isBlank()) return Optional.of(c.getValue());
    }
    return Optional.empty();
  }

  private static void write(HttpServletResponse res, int status, String error) throws IOException {
    res.setStatus(status);
    res.setContentType(MediaType.APPLICATION_JSON_VALUE);
    res.setCharacterEncoding(StandardCharsets.UTF_8.name());
    res.getWriter().write("{\"error\":\"" + error + "\"}");
  }
}
