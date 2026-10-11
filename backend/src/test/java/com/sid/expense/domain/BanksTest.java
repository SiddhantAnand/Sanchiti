package com.sid.expense.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class BanksTest {

  private final Banks banks = new Banks(
      List.of("HDFC Bank", "SBM Bank India", "ICICI Bank", "State Bank of India|SBI", "Bank of India|BOI"));

  @Test
  void mergesSpellingsOfTheSameBank() {
    for (String name : List.of("HDFC", "HDFC Bank", "hdfc bank ltd", "HDFC (Bank)", "HDFC Bank Limited")) {
      assertThat(banks.resolve(name)).as(name).contains("HDFC Bank");
    }
    for (String name : List.of("SBM", "SBM BANK", "SBM Bank India", "SBM BANK (INDIA)", "sbm bank (india) ltd")) {
      assertThat(banks.resolve(name)).as(name).contains("SBM Bank India");
    }
    assertThat(banks.resolve("ICICI")).contains("ICICI Bank");
  }

  @Test
  void keepsSimilarlyNamedBanksApart() {
    assertThat(banks.resolve("SBI")).contains("State Bank of India");
    assertThat(banks.resolve("State Bank of India")).contains("State Bank of India");
    assertThat(banks.resolve("Bank of India")).contains("Bank of India");
    assertThat(banks.resolve("BOI")).contains("Bank of India");
  }

  @Test
  void rejectsNonBanks() {
    for (String name : List.of("quant Mutual Fund", "CDSL", "National Stock Exchange", "BSE", "Bank", "India", "")) {
      assertThat(banks.resolve(name)).as(name).isEmpty();
    }
  }
}
