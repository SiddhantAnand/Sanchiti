package com.sid.expense.domain;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Dates {

  private static final Pattern ISO =
      Pattern.compile("^(\\d{4})-(\\d{1,2})-(\\d{1,2})(?:[T ][\\d:.]+(?:Z|[+-]\\d{2}:?\\d{2})?)?$");
  private static final Pattern DMY = Pattern.compile("^(\\d{1,2})[-/](\\d{1,2})[-/](\\d{4})$");

  private Dates() {}

  /** Accepts YYYY-MM-DD, ISO 8601 timestamps, DD-MM-YYYY and DD/MM/YYYY. */
  public static Optional<LocalDate> parse(String input) {
    if (input == null) return Optional.empty();
    String s = input.trim();
    Matcher m = ISO.matcher(s);
    try {
      if (m.matches()) {
        return Optional.of(LocalDate.of(num(m, 1), num(m, 2), num(m, 3)));
      }
      m = DMY.matcher(s);
      if (m.matches()) {
        return Optional.of(LocalDate.of(num(m, 3), num(m, 2), num(m, 1)));
      }
    } catch (DateTimeException e) {
      return Optional.empty();
    }
    return Optional.empty();
  }

  /** Most recent fully completed Monday–Sunday week before {@code today}. */
  public static Period lastCompletedWeek(LocalDate today) {
    LocalDate to = today.minusDays(today.getDayOfWeek().getValue());
    return new Period(to.minusDays(6), to);
  }

  public static Period weekEnding(LocalDate to) {
    return new Period(to.minusDays(6), to);
  }

  private static int num(Matcher m, int group) {
    return Integer.parseInt(m.group(group));
  }

  public record Period(LocalDate from, LocalDate to) {}
}
