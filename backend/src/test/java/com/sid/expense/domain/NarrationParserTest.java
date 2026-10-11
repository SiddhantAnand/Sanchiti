package com.sid.expense.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.sid.expense.domain.NarrationParser.Narration;
import org.junit.jupiter.api.Test;

class NarrationParserTest {

  @Test
  void parsesUpiP2m() {
    Narration n = NarrationParser.parse("UPI/P2M/830000000042/One Puff/YESB0YBLU/Payment");
    assertThat(n.method()).isEqualTo("UPI");
    assertThat(n.upiType()).isEqualTo("P2M");
    assertThat(n.upiRef()).isEqualTo("830000000042");
    assertThat(n.merchant()).isEqualTo("One Puff");
    assertThat(n.note()).isNull();
  }

  @Test
  void parsesDashSeparatedUpiWithVpa() {
    Narration n = NarrationParser.parse("UPI-ZOMATO LTD-zomato.payu@axisbank-UTIB0000100-412345678901-Order");
    assertThat(n.upiRef()).isEqualTo("412345678901");
    assertThat(n.merchant()).isEqualTo("ZOMATO LTD");
    assertThat(n.vpa()).isEqualTo("zomato.payu@axisbank");
    assertThat(n.note()).isEqualTo("Order");
  }

  @Test
  void parsesCardNarrations() {
    Narration slash = NarrationParser.parse("HDFC Bank Card 4321/SWIGGY PVT LTD FOOD1");
    assertThat(slash.method()).isEqualTo("CARD");
    assertThat(slash.bank()).isEqualTo("HDFC Bank");
    assertThat(slash.cardLast4()).isEqualTo("4321");
    assertThat(slash.merchant()).isEqualTo("SWIGGY PVT LTD FOOD1");

    Narration at = NarrationParser.parse("HDFC Bank Card 4321 at SWIGGY PVT LTD FOOD1");
    assertThat(at.cardLast4()).isEqualTo("4321");
    assertThat(at.merchant()).isEqualTo("SWIGGY PVT LTD FOOD1");

    Narration spent = NarrationParser.parse("Spent on HDFC Bank Card 4321 at LOUNGEONE AI");
    assertThat(spent.bank()).isEqualTo("HDFC Bank");
    assertThat(spent.merchant()).isEqualTo("LOUNGEONE AI");
  }

  @Test
  void parsesDebitEntityNarrations() {
    assertThat(NarrationParser.parse("Debit and Entity: HALLI THO").merchant()).isEqualTo("HALLI THO");
  }

  @Test
  void parsesBankAccountDebits() {
    Narration debit = NarrationParser.parse("Debit - HDFC Bank A/C 1122 to APPLE MEDIA SERVICES");
    assertThat(debit.bank()).isEqualTo("HDFC Bank");
    assertThat(debit.merchant()).isEqualTo("APPLE MEDIA SERVICES");

    Narration sent = NarrationParser.parse("Sent Rs.3800.00 From HDFC Bank A/C *1122 To ASHA RAO");
    assertThat(sent.bank()).isEqualTo("HDFC Bank");
    assertThat(sent.merchant()).isEqualTo("ASHA RAO");
  }

  @Test
  void parsesPayeePhrases() {
    assertThat(NarrationParser.parse("Payment to RAHUL VERMA").merchant()).isEqualTo("RAHUL VERMA");
    assertThat(NarrationParser.parse("Payment at ZOMATO CYBS").merchant()).isEqualTo("ZOMATO CYBS");
    assertThat(NarrationParser.parse("E-Mandate for APPLE MEDIA SERVICES").merchant()).isEqualTo("APPLE MEDIA SERVICES");
    assertThat(NarrationParser.parse("SIP for Groww Invest Tech Private Limited Stocks").merchant())
        .isEqualTo("Groww Invest Tech Private Limited Stocks");
    assertThat(NarrationParser.parse(
            "SIP instalment debit of Rs.2,500.00 for your folio in scheme Edelweiss Small Cap Fund - Regular Plan Growth")
        .merchant()).isEqualTo("Edelweiss Small Cap Fund");
    assertThat(NarrationParser.parse("UPI Payment").merchant()).isNull();
    assertThat(NarrationParser.parse("BSE Trade Confirmation").merchant()).isNull();
  }

  @Test
  void parsesSourcePrefixedFormats() {
    Narration upi = NarrationParser.parse("Debit and UPI/P2M/LAKSHMI W/YESB0YBLU/Payment");
    assertThat(upi.method()).isEqualTo("UPI");
    assertThat(upi.merchant()).isEqualTo("LAKSHMI W");
    assertThat(upi.upiRef()).isNull();

    Narration mandate = NarrationParser.parse("Debit and UPI Mandate/APPLE MEDIA SERVICES");
    assertThat(mandate.method()).isEqualTo("AUTOPAY");
    assertThat(mandate.merchant()).isEqualTo("APPLE MEDIA SERVICES");

    Narration sip = NarrationParser.parse("Investment Deduction and Stocks SIP/Groww Invest Tech Priv");
    assertThat(sip.method()).isEqualTo("AUTOPAY");
    assertThat(sip.merchant()).isEqualTo("Groww Invest Tech Priv");

    assertThat(NarrationParser.parse("Debit and ECMISS/Klook Travel T").merchant()).isEqualTo("Klook Travel T");
    assertThat(NarrationParser.parse("221006/600000000050/00000000/ECMISS/Klook Travel T").merchant())
        .isEqualTo("Klook Travel T");
    assertThat(NarrationParser.parse("PAYMENT ALERT!/Indian Clearing Corporation Lt").merchant())
        .isEqualTo("Indian Clearing Corporation Lt");
    Narration eMandate = NarrationParser.parse("E-Mandate/Groww Invest Tech Private Limited Stocks SIP mandate");
    assertThat(eMandate.merchant()).isEqualTo("Groww Invest Tech Private Limited Stocks SIP mandate");
    assertThat(eMandate.method()).isEqualTo("AUTOPAY");
    assertThat(NarrationParser.parse("Debit and SWIGGY PVT LTD FOOD1").method()).isNull();
  }

  @Test
  void matchesSameMerchantAcrossWordings() {
    assertThat(Texts.sameMerchant(Texts.normalizeReason("Groww Invest Tech Priv"),
        Texts.normalizeReason("Groww Invest Tech Private Limited Stocks"))).isTrue();
    assertThat(Texts.sameMerchant("flipkart minutes", "flipkart minutes")).isTrue();
    assertThat(Texts.sameMerchant("swiggy food", "zomato cybs")).isFalse();
  }

  @Test
  void leavesFreeTextAlone() {
    assertThat(NarrationParser.parse("Chai with friends").merchant()).isNull();
    assertThat(NarrationParser.describeForAi("Chai with friends")).isEqualTo("Chai with friends");
  }

  @Test
  void describesUpiCompactlyForAi() {
    assertThat(NarrationParser.describeForAi("UPI/P2M/330000000090/HALLI THO/HDFC0MERU/Payment"))
        .isEqualTo("HALLI THO (UPI merchant payment)");
  }

  @Test
  void ignoresCompanySuffixesInCategoryKey() {
    assertThat(Texts.normalizeReason("SWIGGY PVT LTD")).isEqualTo(Texts.normalizeReason("Swiggy"));
  }
}
