package com.sid.expense.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SmsParserTest {

  private static final LocalDate RECEIVED = LocalDate.of(2026, 10, 10);

  private static Map<String, Object> debit(String body, String sender) {
    var f = parsed(body, sender);
    assertThat(f).containsEntry("direction", "debit");
    return f;
  }

  private static Map<String, Object> credit(String body, String sender) {
    var f = parsed(body, sender);
    assertThat(f).containsEntry("direction", "credit");
    return f;
  }

  private static Map<String, Object> parsed(String body, String sender) {
    var result = SmsParser.parse(body, sender, RECEIVED);
    assertThat(result).isInstanceOf(SmsParser.Parsed.class);
    return ((SmsParser.Parsed) result).fields();
  }

  @Test
  void readsBoiUpiDebitThatNamesTheCreditedMerchant() {
    var f = debit("BOI UPI -VPA siddhantanand1@ybl linked to Bank of India a/c XX3802 debited Rs.90.00 and credited to "
        + "gpay-11207894803@okbizaxis -Ref 231058425216", "AX-BOIIND-S");
    assertThat(f).containsEntry("reason", "gpay-11207894803@okbizaxis").containsEntry("amount", "90.00")
        .containsEntry("bankName", "Bank of India").containsEntry("paymentMethod", "UPI")
        .containsEntry("upiRef", "231058425216");
  }

  @Test
  void endsPayeeWhereTheBankGluesSentencesTogether() {
    var f = debit("A/c *4321 debited Rs.18000.00 on 12-10-26 to SIDDHANT ANAND.RRN 514533249031.Avl Bal Rs.136.42."
        + "Not you?SMS BLOCK to 9289592895-Indian Bank", "JM-INDBNK-S");
    assertThat(f).containsEntry("reason", "SIDDHANT ANAND").containsEntry("amount", "18000.00")
        .containsEntry("bankName", "Indian Bank").containsEntry("upiRef", "514533249031");
    assertThat(debit("A/c *4321 debited Rs.50.00 on 12-10-26 to CHAI POINT.RRN 514533249032.", ""))
        .containsEntry("reason", "CHAI POINT").containsEntry("bankName", null);
  }

  @Test
  void readsHdfcUpiCreditFromVpa() {
    var f = credit("Rs.500.00 credited to HDFC Bank A/c XX1122 on 05-10-26 from VPA rahul@oksbi "
        + "(UPI 450000000031)", "VM-HDFCBK");
    assertThat(f).containsEntry("amount", "500.00").containsEntry("date", "2026-10-05")
        .containsEntry("reason", "rahul@oksbi").containsEntry("bankName", "HDFC Bank")
        .containsEntry("paymentMethod", "UPI").containsEntry("upiRef", "450000000031")
        .doesNotContainKey("category");
  }

  @Test
  void readsHdfcMoneyReceived() {
    var f = credit("Money Received - INR 1,250.00 in your HDFC Bank A/c xx1122 on 06-10-26 by A/c linked to "
        + "VPA priya@okicici (UPI Ref No 450000000032).", "HDFCBK");
    assertThat(f).containsEntry("amount", "1250.00").containsEntry("reason", "priya@okicici")
        .containsEntry("upiRef", "450000000032");
  }

  @Test
  void readsSbiCreditsAndPrefersTheNameAfterFrom() {
    var deposit = credit("Your A/C XXXXX1234 Credited INR 50,000.00 on 07/10/26 -Deposit by transfer from "
        + "SIDDHANT ANAND.", "SBIINB");
    assertThat(deposit).containsEntry("reason", "SIDDHANT ANAND").containsEntry("amount", "50000.00")
        .containsEntry("bankName", "State Bank of India");

    var upi = credit("Dear SBI UPI User, ur A/cX1234 credited by Rs500.0 on 05Oct26 by (Ref no 450000000033)", "SBIUPI");
    assertThat(upi).containsEntry("amount", "500.0").containsEntry("reason", "Money received")
        .containsEntry("upiRef", "450000000033");
  }

  @Test
  void readsIciciNeftCreditSenderFromInfo() {
    var f = credit("ICICI Bank Account XX123 credited:Rs. 1,00,000.00 on 01-Oct-26. Info NEFT-HDFCN52026100112345-"
        + "ACME TECHNOLOGIES PVT LTD. Available Balance is Rs. 2,00,000.00.", "ICICIT");
    assertThat(f).containsEntry("amount", "100000.00").containsEntry("reason", "ACME TECHNOLOGIES PVT LTD")
        .containsEntry("paymentMethod", "NEFT").containsEntry("date", "2026-10-01");
  }

  @Test
  void tagsSalaryInterestAndRefunds() {
    assertThat(credit("Rs.200 credited to your a/c XX9001. Refund from Swiggy.", "SBMBNK"))
        .containsEntry("reason", "Swiggy").containsEntry("category", "Refund & Cashback");
    assertThat(credit("Rs.349 credited to your a/c XX9001. Refund from Swiggy for order 88123.", "SBMBNK"))
        .containsEntry("reason", "Swiggy");
    assertThat(credit("Your a/c XX9001 is credited with INR 85,000.00 on 30-09-26 towards SALARY SEP 26.", "SBMBNK"))
        .containsEntry("category", "Salary");
    assertThat(credit("Interest of Rs.312.40 has been credited to your A/c XX1122 on 30-09-26.", "HDFCBK"))
        .containsEntry("category", "Interest").containsEntry("reason", "Interest");
  }

  @Test
  void readsDebitThatAlsoNamesTheCreditedPayee() {
    var f = debit("ICICI Bank Acct XX123 debited for Rs 500.00 on 05-Oct-26; RAHUL KUMAR credited. "
        + "UPI:450000000034. Call 18002662 for dispute.", "ICICIT");
    assertThat(f).containsEntry("amount", "500.00").containsEntry("reason", "RAHUL KUMAR");
  }

  @Test
  void skipsCreditsThatAreNotMoneyIn() {
    assertThat(SmsParser.parse("Payment of Rs 10,000.00 received towards your ICICI Bank Credit Card XX7007. Thank you.",
        "ICICIT", RECEIVED)).isEqualTo(new SmsParser.Skip("Card bill payment received"));
    assertThat(SmsParser.parse("Congrats! Get up to Rs 5,00,000 credited to your account instantly. Apply now.",
        "LOANCO", RECEIVED)).isEqualTo(new SmsParser.Skip("Offer, not a credit"));
    assertThat(SmsParser.parse("Your order has been received and will be delivered soon.", "AMAZON", RECEIVED))
        .isEqualTo(new SmsParser.Skip("Not a bank credit"));
    assertThat(SmsParser.parse("Refund of Rs.499 initiated for your order; it will reflect in your account in 5 days.",
        "AMAZON", RECEIVED)).isEqualTo(new SmsParser.Skip("Upcoming, not done yet"));
  }

  @Test
  void readsSbmUpiAndIgnoresPayeeBankRoutingCode() {
    var f = debit("""
        Your account XXXXXXXXXX9001 is debited with INR 228. on 2026-10-08 21:51:13 \
        infor :UPI/P2M/300000000011/Blinkit/HDFC0MERU/UPIInten. SBM BANK (INDIA).""", "AX-SBMBNK-S");
    assertThat(f).containsEntry("date", "2026-10-08").containsEntry("amount", "228")
        .containsEntry("reason", "Blinkit").containsEntry("bankName", "SBM Bank India")
        .containsEntry("paymentMethod", "UPI").containsEntry("upiRef", "300000000011");
  }

  @Test
  void readsHdfcCardWithTimeGluedToDate() {
    var f = debit("Spent Rs.1271 On HDFC Bank Card 4321 At RENTOMOJO On 2026-10-08:12:20:39.Not You? "
        + "To Block+Reissue Call 18002586161/SMS BLOCK DC 4321 to 7308080808", "JD-HDFCBK-S");
    assertThat(f).containsEntry("date", "2026-10-08").containsEntry("amount", "1271")
        .containsEntry("reason", "RENTOMOJO").containsEntry("bankName", "HDFC Bank")
        .containsEntry("paymentMethod", "CARD").containsEntry("cardLast4", "4321");
    assertThat(f.get("upiRef")).isNull();
  }

  @Test
  void readsIciciCardWithTextMonth() {
    var f = debit("INR 900.00 spent using ICICI Bank Card XX7007 on 09-Oct-26 on MADHU WINES. "
        + "Avl Limit: INR 1,00,000.00. If not you, call 1800 2662/SMS BLOCK 7007 to 9215676766.", "VM-ICICIT");
    assertThat(f).containsEntry("date", "2026-10-09").containsEntry("amount", "900.00")
        .containsEntry("reason", "MADHU WINES").containsEntry("bankName", "ICICI Bank")
        .containsEntry("cardLast4", "7007").doesNotContainKey("currency");
  }

  @Test
  void readsForeignCurrencyAndNeverTheAvailableLimit() {
    var f = debit("USD 5.90 spent using ICICI Bank Card XX8006 on 02-May-26 on OPENAI. Avl Limit: INR 7,31,709.19. "
        + "If not you, call 1800 2662/SMS BLOCK 8006 to 9215676766.", "VM-ICICIT");
    assertThat(f).containsEntry("amount", "5.90").containsEntry("currency", "USD")
        .containsEntry("reason", "OPENAI").containsEntry("cardLast4", "8006");
    assertThat(debit("EUR 450.00 spent using ICICI Bank Card XX8006 on 15-Aug-26 on CROUS. Avl Limit: INR 7,02,263.45.",
        "ICICIT")).containsEntry("amount", "450.00").containsEntry("currency", "EUR");
    assertThat(debit("Spent $12.99 on HDFC Bank Card 4321 At NETFLIX On 2026-10-08", "HDFCBK"))
        .containsEntry("amount", "12.99").containsEntry("currency", "USD");
  }

  @Test
  void skipsZeroAmountCardChecks() {
    assertThat(SmsParser.parse("USD .00 spent using ICICI Bank Card XX8006 on 02-May-26 on OPENAI. "
        + "Avl Limit: INR 7,32,269.22. If not you, call 1800 2662/SMS BLOCK 8006 to 9215676766.", "ICICIT", RECEIVED))
        .isEqualTo(new SmsParser.Skip("Zero amount (card check)"));
  }

  @Test
  void neverReadsOnlyABalanceAsTheAmount() {
    assertThat(SmsParser.parse("Card XX8006 spent on 02-May-26 on OPENAI. Avl Limit: INR 7,31,709.19.", "ICICIT",
        RECEIVED)).isEqualTo(new SmsParser.Unreadable("No amount found"));
  }

  @Test
  void readsMultiLineHdfcUpiTransfer() {
    var f = debit("""
        Sent Rs.15000.00
        From HDFC Bank A/C *1122
        To SIDDHANT ANAND
        On 03/10/26
        Ref 800000000050
        Not You?
        Call 18002586161/SMS BLOCK UPI to 7308080808""", null);
    assertThat(f).containsEntry("date", "2026-10-03").containsEntry("amount", "15000.00")
        .containsEntry("reason", "SIDDHANT ANAND").containsEntry("bankName", "HDFC")
        .containsEntry("paymentMethod", "UPI").containsEntry("upiRef", "800000000050");
  }

  @Test
  void readsMandatePathAndUsesReceivedDateWhenTextHasNone() {
    var f = debit("Your a/c XX9001 is debited for Rs.14866.00 infor :221006/600000000050/00000000/ECMISS/Klook Travel T. "
        + "SBM Bank (India)", "SBMBNK");
    assertThat(f).containsEntry("date", "2026-10-10").containsEntry("reason", "Klook Travel T")
        .containsEntry("paymentMethod", "AUTOPAY").containsEntry("upiRef", "600000000050");
  }

  @Test
  void ignoresDatesFarFromWhenTheSmsArrived() {
    var f = debit("Sent Rs.50 To CHAI POINT On 01/01/24 Ref 123456789012", "HDFCBK");
    assertThat(f).containsEntry("date", "2026-10-10");
  }

  @Test
  void readsHdfcMandateAlert() {
    var f = debit("PAYMENT ALERT! \nINR 2500.00 deducted from HDFC Bank A/C No 1122 towards Indian Clearing "
        + "Corporation Lt UMRN: HDFC7000000000000001", "");
    assertThat(f).containsEntry("date", "2026-10-10").containsEntry("amount", "2500.00")
        .containsEntry("reason", "Indian Clearing Corporation Lt").containsEntry("bankName", "HDFC")
        .containsEntry("paymentMethod", "AUTOPAY").containsEntry("upiRef", "HDFC7000000000000001");
  }

  @Test
  void ignoresIfNotYouAtYourEndText() {
    var f = debit("Your account XXXXXXXXXX9001 is debited with INR 256. on 2026-10-01 17:05:59 infor :UPI/P2M/"
        + "050000000000/CHAI KADAI/YESB0MCHU/Payment. The curr bal is INR 1000.00. If not initiated at your end, "
        + "click on the given link to raise a dispute\nhttps://retail.sbm.bank.in/x\nSBM BANK (INDIA).", "");
    assertThat(f).containsEntry("amount", "256").containsEntry("reason", "CHAI KADAI")
        .containsEntry("bankName", "SBM").containsEntry("upiRef", "050000000000");
  }

  @Test
  void stopsPayeeAtBareDateOrBracket() {
    var mandate = debit("UPI Mandate:\nSent Rs.500.00\nfrom HDFC Bank A/c 1122\nTo Groww Invest Tech Priv\n04/10/26\n"
        + "Ref 110000000046\nNot You? Call 18002586161/SMS BLOCK UPI to 7308080808", "");
    assertThat(mandate).containsEntry("reason", "Groww Invest Tech Priv").containsEntry("date", "2026-10-04")
        .containsEntry("paymentMethod", "AUTOPAY").containsEntry("upiRef", "110000000046");

    var transfer = debit("HDFC Bank:Rs. 24600.00 debited from a/c *1122 on 04/10/26 to a/c **0001 "
        + "(UPI Ref No. 450000000023). Not you? Call on 18002586161 to report", "");
    assertThat(transfer).containsEntry("reason", "a/c **0001").containsEntry("amount", "24600.00")
        .containsEntry("upiRef", "450000000023");
  }

  @Test
  void skipsMessagesThatAreNotCompletedPayments() {
    assertThat(SmsParser.parse("123456 is your OTP for a payment of Rs.500 at AMAZON. Do not share.", "HDFCBK", RECEIVED))
        .isEqualTo(new SmsParser.Skip("Not a debit or credit"));
    assertThat(SmsParser.parse("Rs.2500 will be debited from your a/c on 15-Oct-26 towards Groww SIP.", "HDFCBK", RECEIVED))
        .isEqualTo(new SmsParser.Skip("Upcoming, not done yet"));
    assertThat(SmsParser.parse("Txn of Rs.300 debited at ZOMATO failed. Amount will be refunded.", "HDFCBK", RECEIVED))
        .isInstanceOf(SmsParser.Skip.class);
  }

  @Test
  void readsCardSpendWithoutCardDigits() {
    var f = debit("Dear Customer, You have paid INR 2.00 at LOUNGEONE AI             through your SBM Niyo Global "
        + "Credit Card. In case you have not initiated this transaction, report at customercare@sbmbank.co.in "
        + "from your registered email ID. SBM Bank India", "");
    assertThat(f).containsEntry("amount", "2.00").containsEntry("reason", "LOUNGEONE AI")
        .containsEntry("bankName", "SBM").containsEntry("paymentMethod", "CARD");
  }

  @Test
  void skipsBillReminders() {
    assertThat(SmsParser.parse("Dear customer, your Rentomojo invoice for Sep '26 is generated. Total due: ₹1271 "
        + "(incl. previous dues). Please pay by 10-Oct-2026 to avoid late fees. Pay now: https://rmojo.in/x. "
        + "Ignore if paid. –Team Rentomojo", "", RECEIVED))
        .isEqualTo(new SmsParser.Skip("Bill or reminder, not a payment"));
  }

  @Test
  void flagsDebitsItCannotRead() {
    assertThat(SmsParser.parse("Your account has been debited. Check the app for details.", "HDFCBK", RECEIVED))
        .isEqualTo(new SmsParser.Unreadable("No amount found"));
  }

  @Test
  void convertsReceivedTimestampsToIndianDate() {
    assertThat(SmsParser.receivedDate("2026-10-08T20:00:00Z")).isEqualTo(LocalDate.of(2026, 10, 9));
    assertThat(SmsParser.receivedDate("2026-10-08")).isEqualTo(LocalDate.of(2026, 10, 8));
    assertThat(SmsParser.receivedDate("not a date")).isNull();
  }
}
