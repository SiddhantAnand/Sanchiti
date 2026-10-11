package com.sid.expense.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sid.expense.ai.AiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ErrorBody(String error, Object details, String detail) {
    static ErrorBody of(String error) {
      return new ErrorBody(error, null, null);
    }
  }

  @ExceptionHandler(ApiException.class)
  ResponseEntity<ErrorBody> api(ApiException e) {
    return ResponseEntity.status(e.status()).body(new ErrorBody(e.getMessage(), e.details(), null));
  }

  @ExceptionHandler(AiException.class)
  ResponseEntity<ErrorBody> ai(AiException e) {
    log.warn("[ai] {}", e.getMessage());
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ErrorBody("AI request failed", null, e.getMessage()));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ErrorBody> unreadable(HttpMessageNotReadableException e) {
    Throwable cause = e.getMostSpecificCause();
    String where = cause == null || cause.getMessage() == null ? null : cause.getMessage().replaceAll("\\s+", " ");
    if (where != null && where.length() > 300) where = where.substring(0, 300);
    return ResponseEntity.badRequest().body(new ErrorBody("Body must be valid JSON", null, where));
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ResponseEntity<ErrorBody> badParam(MethodArgumentTypeMismatchException e) {
    return ResponseEntity.badRequest().body(ErrorBody.of("Invalid value for '" + e.getName() + "'"));
  }

  @ExceptionHandler(NoResourceFoundException.class)
  ResponseEntity<ErrorBody> notFound() {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorBody.of("Not found"));
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  ResponseEntity<ErrorBody> methodNotAllowed() {
    return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(ErrorBody.of("Method not allowed"));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ErrorBody> unexpected(Exception e) {
    log.error("Unhandled error", e);
    return ResponseEntity.internalServerError().body(ErrorBody.of("Internal server error"));
  }
}
