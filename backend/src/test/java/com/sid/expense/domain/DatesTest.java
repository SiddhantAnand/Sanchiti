package com.sid.expense.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class DatesTest {

  @ParameterizedTest
  @CsvSource({
    "2026-10-05, 2026-10-05",
    "2026-10-05T21:15:00+05:30, 2026-10-05",
    "05-10-2026, 2026-10-05",
    "5/10/2026, 2026-10-05",
  })
  void parsesSupportedFormats(String input, LocalDate expected) {
    assertThat(Dates.parse(input)).contains(expected);
  }

  @ParameterizedTest
  @ValueSource(strings = {"2026-02-30", "31-04-2026", "yesterday", "2026/10/05", ""})
  void rejectsInvalidDates(String input) {
    assertThat(Dates.parse(input)).isEmpty();
  }

  @Test
  void lastCompletedWeekIsPreviousMondayToSunday() {
    // 2026-10-11 is a Sunday, so the current week is not complete yet.
    assertThat(Dates.lastCompletedWeek(LocalDate.parse("2026-10-11")))
        .isEqualTo(new Dates.Period(LocalDate.parse("2026-09-28"), LocalDate.parse("2026-10-04")));
    assertThat(Dates.lastCompletedWeek(LocalDate.parse("2026-10-12")))
        .isEqualTo(new Dates.Period(LocalDate.parse("2026-10-05"), LocalDate.parse("2026-10-11")));
  }
}
