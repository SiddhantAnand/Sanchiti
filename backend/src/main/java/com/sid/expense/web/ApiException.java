package com.sid.expense.web;

import org.springframework.http.HttpStatus;

/** Thrown by controllers to return {@code {"error": ..., "details": ...}} with the given status. */
public class ApiException extends RuntimeException {

  private final HttpStatus status;
  private final transient Object details;

  public ApiException(HttpStatus status, String message) {
    this(status, message, null);
  }

  public ApiException(HttpStatus status, String message, Object details) {
    super(message);
    this.status = status;
    this.details = details;
  }

  public HttpStatus status() {
    return status;
  }

  public Object details() {
    return details;
  }
}
