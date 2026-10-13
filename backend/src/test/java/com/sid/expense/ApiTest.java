package com.sid.expense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sid.expense.ai.AiClient;
import com.sid.expense.web.AccountService;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ApiTest.StubAiConfig.class)
class ApiTest {

  static final JsonMapper JSON = JsonMapper.builder().build();

  static final Map<String, Object> INSIGHTS = Map.of(
      "headline", "Food led the week",
      "summary", "You spent ₹1,250 this week.",
      "highlights", List.of("Food & Dining was the top category"),
      "concerns", List.of(),
      "savingTips", List.of("Cook at home twice a week"),
      "unusualSpends", List.of());

  /** Swiggy/Puff -> food, SIP/Groww -> investments, everything else -> shopping. */
  static class StubAi implements AiClient {
    final List<String> calls = new CopyOnWriteArrayList<>();

    @Override
    public boolean isEnabled() {
      return true;
    }

    @Override
    public AiResult generate(String system, String prompt, Map<String, Object> schema) {
      if (system.startsWith("You read SMS")) {
        calls.add("sms");
        List<?> entries = JSON.readValue(prompt, List.class);
        return new AiResult(entries.stream().map(e -> (Map<?, ?>) e).map(StubAi::readSms).toList(), "stub", null);
      }
      if ("ARRAY".equals(schema.get("type"))) {
        calls.add("categorize");
        List<?> entries = JSON.readValue(prompt, List.class);
        List<Map<String, Object>> data = entries.stream()
            .map(e -> (Map<?, ?>) e)
            .map(e -> Map.<String, Object>of(
                "i", e.get("i"),
                "category", category((String) e.get("entry"))))
            .toList();
        return new AiResult(data, "stub", null);
      }
      calls.add("analysis");
      return new AiResult(INSIGHTS, "stub", Map.of("totalTokenCount", 42));
    }

    /** Reads the mutual fund bill SMS, refuses notices, and invents an amount for "MYSTERY" (which must be dropped). */
    private static Map<String, Object> readSms(Map<?, ?> e) {
      String sms = (String) e.get("sms");
      Map<String, Object> r = new java.util.HashMap<>();
      r.put("i", e.get("i"));
      r.put("isTransaction", !sms.contains("applying"));
      if (sms.contains("Bill Paid")) {
        r.putAll(Map.of("direction", "debit", "amount", 2500, "date", "2026-10-12", "payee", "EdelweisMF",
            "bank", "HDFC Bank", "paymentMethod", "NETBANKING"));
      } else if (sms.contains("MYSTERY")) {
        r.putAll(Map.of("direction", "debit", "amount", 9999, "payee", "Mystery shop"));
      }
      return r;
    }

    private static String category(String entry) {
      if (entry.matches("(?i).*(swiggy|puff).*")) return "Food & Dining";
      if (entry.matches("(?i).*(sip|groww).*")) return "Investments & Savings";
      return "Shopping";
    }
  }

  @TestConfiguration
  static class StubAiConfig {
    @Bean
    @Primary
    StubAi stubAi() {
      return new StubAi();
    }
  }

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired StubAi ai;
  @Autowired AccountService accounts;

  /** The owner's web session (login "siddhant" from application-test.properties). */
  Cookie session;

  @BeforeEach
  void reset() throws Exception {
    jdbc.sql("""
        TRUNCATE users, invites, transactions, reason_categories, analyses, review, sessions
        RESTART IDENTITY CASCADE""").update();
    accounts.ensureOwner();
    session = signIn("siddhant", "test-password");
    ai.calls.clear();
  }

  private Cookie signIn(String login, String password) throws Exception {
    String setCookie = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content(JSON.writeValueAsString(Map.of("login", login, "password", password))))
        .andExpect(status().isOk())
        .andReturn().getResponse().getHeader("Set-Cookie");
    return new Cookie("session", setCookie.replaceAll("^session=([^;]+).*", "$1"));
  }

  private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
    return as(session, b);
  }

  private static MockHttpServletRequestBuilder as(Cookie cookie, MockHttpServletRequestBuilder b) {
    return b.cookie(cookie).header("X-Requested-With", "fetch");
  }

  private ResultActions postTx(String json) throws Exception {
    return postTx(json, "");
  }

  private ResultActions postTx(String json, String query) throws Exception {
    return mvc.perform(authed(post("/api/transactions" + query)).contentType(MediaType.APPLICATION_JSON).content(json));
  }

  private ResultActions postSms(String body, String key) throws Exception {
    return mvc.perform(post("/api/transactions/sms").header("Authorization", "Bearer " + key)
        .contentType(MediaType.APPLICATION_JSON).content(body));
  }

  private int count() {
    return jdbc.sql("SELECT COUNT(*) FROM transactions").query(Integer.class).single();
  }

  @Test
  void storesTransactionWithInrDefaultAndAiCategory() throws Exception {
    postTx("""
        {"date": "2026-10-05", "amount": 450.5, "reason": "Swiggy order 123"}""")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.inserted[0].date").value("2026-10-05"))
        .andExpect(jsonPath("$.inserted[0].amount").value(450.5))
        .andExpect(jsonPath("$.inserted[0].currency").value("INR"))
        .andExpect(jsonPath("$.inserted[0].category").value("Food & Dining"))
        .andExpect(jsonPath("$.inserted[0].categorySource").value("ai"));
  }

  @Test
  void reusesKnownCategoryWithoutCallingAiAgain() throws Exception {
    postTx("""
        {"date": "2026-10-05", "amount": 300, "reason": "Swiggy order 123"}""");
    postTx("""
        {"date": "2026-10-06", "amount": 200, "reason": "swiggy order #999"}""")
        .andExpect(jsonPath("$.inserted[0].category").value("Food & Dining"));
    assertThat(ai.calls).containsExactly("categorize");
  }

  @Test
  void rejectsInvalidItemsWithoutSavingAny() throws Exception {
    postTx("""
        [{"date": "2026-10-05", "amount": 100, "reason": "ok"},
         {"date": "2026-10-05", "amount": -5, "reason": "bad"}]""")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value(containsString("Item 1: amount: Amount must be greater than 0")));
    postTx("{\"date\": \"2026-10-05\", \"reason\": \"x\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("amount: Amount is missing"));
    postTx("""
        {"date": "2026-10-05", "amount": 10, "reason": "x", "bankName": "quant Mutual Fund"}""")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("Unknown bank: quant Mutual Fund"));
    postTx("{not json").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("Body must be valid JSON"));
    assertThat(count()).isZero();
  }

  @Test
  void skipsDuplicatesUnlessAllowed() throws Exception {
    String tx = """
        {"date": "2026-10-05", "amount": 99, "reason": "Chai", "currency": "inr"}""";
    postTx(tx).andExpect(status().isCreated());
    postTx(tx)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.inserted", empty()))
        .andExpect(jsonPath("$.duplicateIndexes[0]").value(0));
    postTx(tx, "?allowDuplicates=true").andExpect(status().isCreated());
  }

  @Test
  void summarisesInrAndKeepsOtherCurrenciesSeparate() throws Exception {
    postTx("""
        [{"date": "2026-10-01", "amount": 1000, "reason": "Swiggy dinner", "bankName": "HDFC"},
         {"date": "2026-10-02", "amount": 250, "reason": "Amazon cable", "bankName": "SBI"},
         {"date": "2026-10-02", "amount": 20, "reason": "Duty free", "currency": "USD"}]""");
    mvc.perform(authed(get("/api/summary?from=2026-10-01&to=2026-10-07")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1250.0))
        .andExpect(jsonPath("$.count").value(2))
        .andExpect(jsonPath("$.averagePerDay").value(178.57))
        .andExpect(jsonPath("$.byCategory[0].category").value("Food & Dining"))
        .andExpect(jsonPath("$.byCategory[0].total").value(1000.0))
        .andExpect(jsonPath("$.byCategory[0].sharePct").value(80.0))
        .andExpect(jsonPath("$.byBank[0].bank").value("HDFC Bank"))
        .andExpect(jsonPath("$.byBank[1].bank").value("State Bank of India"))
        .andExpect(jsonPath("$.otherCurrencies[0].currency").value("USD"))
        .andExpect(jsonPath("$.otherCurrencies[0].total").value(20.0))
        .andExpect(jsonPath("$.daily", hasSize(7)));
  }

  @Test
  void searchesByTextOrAmount() throws Exception {
    postTx("""
        [{"date": "2026-10-01", "amount": 2500, "reason": "Indian Clearing Corporation Lt"},
         {"date": "2026-10-02", "amount": 250.50, "reason": "Amazon cable"},
         {"date": "2026-10-03", "amount": 99, "reason": "Chai 2500 cups"}]""");
    String url = "/api/transactions?from=2026-10-01&to=2026-10-31&q=";
    mvc.perform(authed(get(url + "2500"))).andExpect(jsonPath("$.items[*].amount", contains(2500.0)));
    mvc.perform(authed(get(url + "₹2,500.00"))).andExpect(jsonPath("$.total").value(1));
    mvc.perform(authed(get(url + "250.5"))).andExpect(jsonPath("$.items[0].reason").value("Amazon cable"));
    mvc.perform(authed(get(url + ">250"))).andExpect(jsonPath("$.total").value(2));
    mvc.perform(authed(get(url + "<100"))).andExpect(jsonPath("$.items[0].amount").value(99.0));
    mvc.perform(authed(get(url + "100-3000"))).andExpect(jsonPath("$.total").value(2));
    mvc.perform(authed(get(url + "cable"))).andExpect(jsonPath("$.total").value(1));
    mvc.perform(authed(get(url + "99999999999999999999999"))).andExpect(status().isOk());
  }

  @Test
  void keepsInvestmentsSelfTransfersAndCardBillsOutOfSpending() throws Exception {
    postTx("""
        [{"date": "2026-10-06", "amount": 300, "reason": "Swiggy dinner"},
         {"date": "2026-10-06", "amount": 450, "reason": "SIP for Groww Invest Tech Private Limited Stocks"},
         {"date": "2026-10-06", "amount": 15000, "reason": "SIDDHANT ANAND"},
         {"date": "2026-10-06", "amount": 6356.78, "reason": "CRED Club"},
         {"date": "2026-10-06", "amount": 500, "reason": "Siddhant Sharma"}]""")
        .andExpect(jsonPath("$.inserted[2].category").value("Self Transfer"))
        .andExpect(jsonPath("$.inserted[2].categorySource").value("rule"))
        .andExpect(jsonPath("$.inserted[3].category").value("Card Bill Payment"))
        .andExpect(jsonPath("$.inserted[4].category").value("Shopping"));
    mvc.perform(authed(get("/api/summary?from=2026-10-01&to=2026-10-07")))
        .andExpect(jsonPath("$.total").value(800.0))
        .andExpect(jsonPath("$.count").value(2))
        .andExpect(jsonPath("$.invested").value(450.0))
        .andExpect(jsonPath("$.investedCount").value(1))
        .andExpect(jsonPath("$.ownTransfers").value(21356.78))
        .andExpect(jsonPath("$.ownTransferCount").value(2))
        .andExpect(jsonPath("$.deductedByBank[0].bank").value("Unknown bank"))
        .andExpect(jsonPath("$.deductedByBank[0].count").value(5))
        .andExpect(jsonPath("$.deductedByBank[0].total").value(22606.78))
        .andExpect(jsonPath("$.deductedByBank[0].spent").value(800.0))
        .andExpect(jsonPath("$.deductedByBank[0].invested").value(450.0))
        .andExpect(jsonPath("$.deductedByBank[0].ownTransfers").value(21356.78));

    // Clicking a slice of a bank's bar lists exactly the rows behind it.
    String list = "/api/transactions?from=2026-10-01&to=2026-10-07&bank=Unknown bank&kind=";
    mvc.perform(authed(get(list + "spent"))).andExpect(jsonPath("$.items[*].amount", containsInAnyOrder(300.0, 500.0)));
    mvc.perform(authed(get(list + "invested"))).andExpect(jsonPath("$.items[*].amount", contains(450.0)));
    mvc.perform(authed(get(list + "ownTransfers"))).andExpect(jsonPath("$.total").value(2));
    mvc.perform(authed(get(list + "bogus"))).andExpect(status().isBadRequest());
    mvc.perform(authed(get("/api/transactions?bank=HDFC Bank"))).andExpect(jsonPath("$.total").value(0));
  }

  @Test
  void readsRawSmsWithPhoneKeyAndSkipsResends() throws Exception {
    String body = """
        {"messages": [
          {"body": "Spent Rs.1271 On HDFC Bank Card 4321 At RENTOMOJO On 2026-10-08:12:20:39.Not You?",
           "sender": "JD-HDFCBK-S", "received": "2026-10-08"},
          {"body": "123456 is your OTP. Do not share.", "sender": "JD-HDFCBK-S", "received": "2026-10-08"},
          {"body": "Your account has been debited. Check the app.", "sender": "AX-SBMBNK-S", "received": "2026-10-08"}
        ]}""";

    mvc.perform(post("/api/transactions/sms?dryRun=true").header("Authorization", "Bearer test-phone-key")
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.parsed[0].fields.reason").value("RENTOMOJO"))
        .andExpect(jsonPath("$.skipped[0].index").value(1))
        .andExpect(jsonPath("$.review[0].issue").value("SMS: No amount found; Gemini couldn't read it either"));
    assertThat(count()).isZero();

    postSms(body, "test-phone-key")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.messages").value(3))
        .andExpect(jsonPath("$.inserted[0].date").value("2026-10-08"))
        .andExpect(jsonPath("$.inserted[0].amount").value(1271.0))
        .andExpect(jsonPath("$.inserted[0].bank").value("HDFC Bank"))
        .andExpect(jsonPath("$.inserted[0].cardLast4").value("4321"))
        .andExpect(jsonPath("$.review[0].index").value(2));

    postSms(body, "test-phone-key")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.inserted", empty()))
        .andExpect(jsonPath("$.duplicateIndexes", contains(0)));
    assertThat(count()).isEqualTo(1);
  }

  @Test
  void acceptsCommaJoinedSmsObjectsFromShortcuts() throws Exception {
    String body = """
        {"receiver":"2026-10-01","body":"Sent Rs.189.00\\nFrom HDFC Bank A\\/C *1122\\nTo CTRLX TECHNOLOGIES PRIVAT\\n\
        On 01\\/10\\/26\\nRef 660000000025","sender":""},\
        {"receiver":"2026-10-01","body":"Your OTP is 1234","sender":""}""";
    mvc.perform(post("/api/transactions/sms?dryRun=true").header("Authorization", "Bearer test-phone-key")
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.messages").value(2))
        .andExpect(jsonPath("$.parsed[0].fields.reason").value("CTRLX TECHNOLOGIES PRIVAT"))
        .andExpect(jsonPath("$.parsed[0].fields.date").value("2026-10-01"))
        .andExpect(jsonPath("$.skipped[0].index").value(1));
  }

  @Test
  void keepsSameChargeOnTwoDifferentCardsButDropsRepeatedSms() throws Exception {
    String body = """
        [{"received": "2026-10-06", "sender": "",
          "body": "Spent Rs.2 On HDFC Bank Card 4321 At LOUNGEONE AI On 2026-10-06:01:23:44.Not You?"},
         {"received": "2026-10-06", "sender": "",
          "body": "You have paid INR 2.00 at LOUNGEONE AI through your SBM Niyo Global Credit Card. SBM Bank India"},
         {"received": "2026-10-06", "sender": "",
          "body": "You have paid INR 2.00 at LOUNGEONE AI through your SBM Niyo Global Credit Card. SBM Bank India"}]""";
    postSms(body, "test-phone-key")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.inserted", hasSize(2)))
        .andExpect(jsonPath("$.duplicateIndexes", contains(2)));
  }

  @Test
  void manualCategoryOverrideAppliesToSameMerchant() throws Exception {
    String body = postTx("""
        [{"date": "2026-10-01", "amount": 500, "reason": "Gym fee"},
         {"date": "2026-09-01", "amount": 500, "reason": "Gym fee"}]""")
        .andReturn().getResponse().getContentAsString();
    String id = JSON.readTree(body).get("inserted").get(0).get("id").asString();

    patchTx(id, "{\"category\": \"Health & Medical\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.category").value("Health & Medical"));

    List<String> sources = jdbc.sql("SELECT category || '/' || category_source FROM transactions").query(String.class).list();
    assertThat(sources).containsOnly("Health & Medical/manual");
  }

  @Test
  void asksGeminiOnlyForSmsTheRulesCannotReadAndChecksItsAnswer() throws Exception {
    String body = """
        [{"received": "2026-10-12", "sender": "",
          "body": "Bill Paid! EdelweisMF Bill EDKFINTB-B10242 of Rs. 2500.00 paid on 12-Oct-2026 from HDFC Bank Account XX0536."},
         {"received": "2026-10-12", "sender": "",
          "body": "Thank you for applying for a Credit Card. An SMS will be sent shortly."},
         {"received": "2026-10-12", "sender": "", "body": "MYSTERY debited from your a/c. Check the app."},
         {"received": "2026-10-12", "sender": "HDFCBK",
          "body": "Sent Rs.50.00 From HDFC Bank A/C *1122 To CHAI POINT On 12/10/26 Ref 660000000201"}]""";
    postSms(body, "test-phone-key")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.inserted", hasSize(2)))
        .andExpect(jsonPath("$.inserted[*].reason", containsInAnyOrder("EdelweisMF", "CHAI POINT")))
        .andExpect(jsonPath("$.inserted[?(@.reason == 'EdelweisMF')].note").value(contains("Read from SMS by Gemini")))
        .andExpect(jsonPath("$.inserted[?(@.reason == 'EdelweisMF')].bank").value(contains("HDFC Bank")))
        .andExpect(jsonPath("$.skipped[*].why", contains("Gemini: not a bank transaction")))
        .andExpect(jsonPath("$.review[0].index").value(2))
        .andExpect(jsonPath("$.review[0].issue").value(containsString("Gemini couldn't read it either")));
    // One Gemini call for the two messages the rules couldn't read, none for the one they could.
    assertThat(ai.calls.stream().filter("sms"::equals)).hasSize(1);
  }

  @Test
  void retriesWaitingReviewItemsWithCurrentRulesAndGemini() throws Exception {
    String sms = """
        {"received": "2022-11-06", "sender": "", "body": "BOI UPI -VPA siddhantanand1@ybl linked to Bank of India \
        a/c XX3802 debited Rs.90.00 and credited to gpay-11207894803@okbizaxis -Ref 231058425216"}""";
    String notice = """
        {"received": "2026-10-11", "sender": "", "body": "Thank you for applying for a Credit Card."}""";
    jdbc.sql("INSERT INTO review (user_id, payload, issue, payload_hash) VALUES (1, CAST(? AS jsonb), 'SMS: No payee found', 'a')")
        .param(sms).update();
    jdbc.sql("INSERT INTO review (user_id, payload, issue, payload_hash) VALUES (1, CAST(? AS jsonb), 'SMS: No amount found', 'b')")
        .param(notice).update();

    mvc.perform(authed(post("/api/review/retry")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.saved").value(1))
        .andExpect(jsonPath("$.remaining").value(1));
    mvc.perform(authed(get("/api/transactions?from=2022-11-01&to=2022-11-30")))
        .andExpect(jsonPath("$.items[0].reason").value("gpay-11207894803@okbizaxis"))
        .andExpect(jsonPath("$.items[0].bank").value("Bank of India"));
    mvc.perform(authed(get("/api/review")))
        .andExpect(jsonPath("$.items[0].issue").value(containsString("discard it if that's right")));
  }

  @Test
  void remembersHandSetCategoryForTheNextPaymentToTheSamePayee() throws Exception {
    String sms = """
        [{"received": "%s", "sender": "HDFCBK",
          "body": "Sent Rs.%s From HDFC Bank A/C *1122 To RAMESH KIRANA On %s Ref %s"}]""";
    String body = postSms(sms.formatted("2026-10-01", "120.00", "01/10/26", "660000000101"), "test-phone-key")
        .andReturn().getResponse().getContentAsString();
    String id = JSON.readTree(body).get("inserted").get(0).get("id").asString();
    patchTx(id, "{\"category\": \"Groceries\"}").andExpect(status().isOk());
    ai.calls.clear();

    postSms(sms.formatted("2026-10-05", "80.00", "05/10/26", "660000000102"), "test-phone-key")
        .andExpect(jsonPath("$.inserted[0].category").value("Groceries"))
        .andExpect(jsonPath("$.inserted[0].categorySource").value("manual"));
    assertThat(ai.calls).isEmpty();
  }

  @Test
  void readsCreditSmsAndKeepsThemOutOfSpending() throws Exception {
    String body = """
        [{"received": "2026-10-05", "sender": "VM-HDFCBK",
          "body": "Rs.2,000.00 credited to HDFC Bank A/c XX1122 on 05-10-26 from VPA rahul@oksbi (UPI 450000000041)"},
         {"received": "2026-10-05", "sender": "SBMBNK", "body": "Rs.200 credited to your a/c XX9001. Refund from Swiggy."},
         {"received": "2026-10-05", "sender": "VM-HDFCBK",
          "body": "Sent Rs.500.00 From HDFC Bank A/C *1122 To SWIGGY On 05/10/26 Ref 660000000041"}]""";
    postSms(body, "test-phone-key")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.inserted[0].direction").value("credit"))
        .andExpect(jsonPath("$.inserted[0].category").value("Received"))
        .andExpect(jsonPath("$.inserted[1].category").value("Refund & Cashback"))
        .andExpect(jsonPath("$.inserted[2].direction").value("debit"))
        .andExpect(jsonPath("$.inserted[2].category").value("Food & Dining"));
    // Credits never go to Gemini.
    assertThat(ai.calls).containsExactly("categorize");

    mvc.perform(authed(get("/api/summary?from=2026-10-01&to=2026-10-07")))
        .andExpect(jsonPath("$.total").value(500.0))
        .andExpect(jsonPath("$.count").value(1))
        .andExpect(jsonPath("$.received").value(2200.0))
        .andExpect(jsonPath("$.receivedCount").value(2))
        .andExpect(jsonPath("$.net").value(1700.0))
        .andExpect(jsonPath("$.receivedByCategory[0].category").value("Received"))
        .andExpect(jsonPath("$.largestReceived[0].amount").value(2000.0))
        .andExpect(jsonPath("$.creditedByBank[*].bank", containsInAnyOrder("HDFC Bank", "SBM Bank India")));

    String list = "/api/transactions?from=2026-10-01&to=2026-10-07&direction=";
    mvc.perform(authed(get(list + "credit"))).andExpect(jsonPath("$.total").value(2));
    mvc.perform(authed(get(list + "debit"))).andExpect(jsonPath("$.items[*].amount", contains(500.0)));
    mvc.perform(authed(get(list + "sideways"))).andExpect(status().isBadRequest());
    mvc.perform(authed(get("/api/transactions/span")))
        .andExpect(jsonPath("$.first").value("2026-10-05")).andExpect(jsonPath("$.last").value("2026-10-05"));
  }

  @Test
  void pairsBothSidesOfAMoveBetweenOwnAccounts() throws Exception {
    postTx("""
        [{"date": "2026-10-04", "amount": 24600, "reason": "SIDDHANT ANAND", "bankName": "HDFC"},
         {"date": "2026-10-04", "amount": 24600, "reason": "a/c XX0001", "bankName": "ICICI", "direction": "credit"},
         {"date": "2026-10-04", "amount": 999, "reason": "Rahul", "bankName": "ICICI", "direction": "credit"}]""")
        .andExpect(jsonPath("$.inserted[0].category").value("Self Transfer"))
        .andExpect(jsonPath("$.inserted[1].category").value("Self Transfer"))
        .andExpect(jsonPath("$.inserted[2].category").value("Received"));
    mvc.perform(authed(get("/api/summary?from=2026-10-01&to=2026-10-07")))
        .andExpect(jsonPath("$.total").value(0.0))
        .andExpect(jsonPath("$.received").value(999.0))
        .andExpect(jsonPath("$.ownTransfersIn").value(24600.0))
        .andExpect(jsonPath("$.creditedByBank[0].ownTransfers").value(24600.0));
  }

  @Test
  void flipsDirectionAndKeepsCategoriesOnTheRightSide() throws Exception {
    String body = postTx("""
        {"date": "2026-10-02", "amount": 300, "reason": "Puff corner"}""").andReturn().getResponse().getContentAsString();
    String id = JSON.readTree(body).get("inserted").get(0).get("id").asString();

    patchTx(id, "{\"category\": \"Salary\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value(containsString("Salary is not a debit category")));
    patchTx(id, "{\"direction\": \"credit\"}")
        .andExpect(jsonPath("$.direction").value("credit"))
        .andExpect(jsonPath("$.category").value("Received"));
    patchTx(id, "{\"category\": \"Refund & Cashback\"}").andExpect(jsonPath("$.category").value("Refund & Cashback"));
    postTx("""
        {"date": "2026-10-03", "amount": 50, "reason": "x", "category": "Groceries", "direction": "credit"}""")
        .andExpect(status().isBadRequest());
    mvc.perform(authed(get("/api/categories")))
        .andExpect(jsonPath("$.creditCategories", contains("Self Transfer", "Salary", "Refund & Cashback", "Interest",
            "Received")))
        .andExpect(jsonPath("$.categories", not(org.hamcrest.Matchers.hasItem("Salary"))));
  }

  @Test
  void detectsSamePaymentReportedByAnotherSource() throws Exception {
    postTx("""
        [{"date": "2026-10-08", "amount": 195, "reason": "Debit - HDFC Bank A/C 1122 to APPLE MEDIA SERVICES"},
         {"date": "2026-10-08", "amount": 500, "reason": "Debit - HDFC Bank A/C 1122 to Groww Invest Tech Priv"},
         {"date": "2026-10-05", "amount": 144.89, "reason": "UPI/P2M/512345678901/Ownly/YESB0YBLU/Payment"}]""")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.inserted[0].bank").value("HDFC Bank"))
        .andExpect(jsonPath("$.inserted[0].merchant").value("APPLE MEDIA SERVICES"));

    postTx("""
        [{"date": "2026-10-08", "amount": 195, "reason": "E-Mandate for APPLE MEDIA SERVICES"},
         {"date": "2026-10-08", "amount": 500, "reason": "SIP for Groww Invest Tech Private Limited Stocks"},
         {"date": "2026-10-05", "amount": 144.89, "reason": "Sent Rs.144.89 From HDFC Bank A/C *1122 To Ownly"},
         {"date": "2026-10-08", "amount": 196, "reason": "E-Mandate for APPLE MEDIA SERVICES"}]""")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.duplicateIndexes", containsInAnyOrder(0, 1, 2)))
        .andExpect(jsonPath("$.inserted", hasSize(1)));
    assertThat(count()).isEqualTo(4);
  }

  @Test
  void runsWeeklyAnalysisOnceThenServesCachedResult() throws Exception {
    postTx("""
        [{"date": "2026-10-05", "amount": 1000, "reason": "Swiggy dinner"},
         {"date": "2026-10-07", "amount": 250, "reason": "Amazon cable"}]""");
    mvc.perform(authed(post("/api/analysis/run?to=2026-10-11"))).andExpect(status().isCreated());
    mvc.perform(authed(post("/api/analysis/run?to=2026-10-11")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("cached"));
    assertThat(ai.calls.stream().filter("analysis"::equals)).hasSize(1);

    mvc.perform(authed(get("/api/analysis/latest")))
        .andExpect(jsonPath("$.insights.headline").value("Food led the week"))
        .andExpect(jsonPath("$.stats.week.total").value(1250.0));
  }

  private String firstInsertedId(String json) throws Exception {
    return postTx(json).andReturn().getResponse().getContentAsString()
        .transform(b -> JSON.readTree(b).get("inserted").get(0).get("id").asString());
  }

  private ResultActions patchTx(String id, String json) throws Exception {
    return mvc.perform(authed(patch("/api/transactions/" + id)).contentType(MediaType.APPLICATION_JSON).content(json));
  }

  @Test
  void editsFieldsAndRecategorisesWhenMerchantChanges() throws Exception {
    String id = firstInsertedId("""
        {"date": "2026-10-05", "amount": 300, "reason": "Gym fee", "bankName": "HDFC"}""");

    patchTx(id, """
        {"reason": "Swiggy dinner", "amount": "320.50", "note": "team lunch", "bankName": "SBM BANK"}""")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.amount").value(320.5))
        .andExpect(jsonPath("$.date").value("2026-10-05"))
        .andExpect(jsonPath("$.note").value("team lunch"))
        .andExpect(jsonPath("$.bank").value("SBM Bank India"))
        .andExpect(jsonPath("$.category").value("Food & Dining"))
        .andExpect(jsonPath("$.categorySource").value("ai"));

    patchTx(id, "{\"amount\": -1}").andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value(containsString("amount:")));
    patchTx(id, "{\"bankName\": \"Nowhere Bank\"}").andExpect(status().isBadRequest());
    patchTx(id, "{\"id\": \"x\"}").andExpect(status().isBadRequest());
    patchTx("00000000-0000-0000-0000-000000000000", "{\"note\": \"x\"}").andExpect(status().isNotFound());
  }

  @Test
  void nullCategoryHandsTransactionBackToGemini() throws Exception {
    String id = firstInsertedId("""
        {"date": "2026-10-05", "amount": 300, "reason": "Swiggy dinner"}""");
    patchTx(id, "{\"category\": \"Travel\"}").andExpect(jsonPath("$.categorySource").value("manual"));
    patchTx(id, "{\"category\": null}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.category").value("Food & Dining"))
        .andExpect(jsonPath("$.categorySource").value("ai"));
  }

  @Test
  void resolvesOrDiscardsReviewItems() throws Exception {
    postSms("""
        [{"body": "INR 2500.00 deducted from your a/c. Ref: mandate", "received": "2026-10-03"},
         {"body": "Your account has been debited. Check the app.", "received": "2026-10-03"}]""", "test-phone-key");
    List<Long> ids = jdbc.sql("SELECT id FROM review ORDER BY id").query(Long.class).list();
    assertThat(ids).hasSize(2);
    String url = "/api/review/" + ids.get(0) + "/resolve";

    mvc.perform(authed(get("/api/review"))).andExpect(jsonPath("$.items", hasSize(2)));
    mvc.perform(authed(post(url)).contentType(MediaType.APPLICATION_JSON).content("""
            {"amount": 2500, "reason": "Indian Clearing Corporation Lt"}"""))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("date: Date is missing"));
    mvc.perform(authed(post(url)).contentType(MediaType.APPLICATION_JSON).content("""
            {"date": "2026-10-03", "amount": 2500, "reason": "Indian Clearing Corporation Lt", "note": "SIP"}"""))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.date").value("2026-10-03"))
        .andExpect(jsonPath("$.note").value("SIP"));
    mvc.perform(authed(post(url)).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isNotFound());

    mvc.perform(authed(delete("/api/review/" + ids.get(1)))).andExpect(status().isNoContent());
    mvc.perform(authed(delete("/api/review/" + ids.get(1)))).andExpect(status().isNotFound());
    assertThat(count()).isEqualTo(1);
    assertThat(jdbc.sql("SELECT COUNT(*) FROM review").query(Integer.class).single()).isZero();
  }

  @Test
  void requiresSignInOnApiButNotHealth() throws Exception {
    mvc.perform(get("/api/categories")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/categories").cookie(new Cookie("session", "forged"))).andExpect(status().isUnauthorized());
    mvc.perform(authed(get("/api/categories"))).andExpect(status().isOk());
    mvc.perform(get("/health")).andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
  }

  @Test
  void phoneKeyCanOnlySendSms() throws Exception {
    mvc.perform(get("/api/transactions").header("Authorization", "Bearer test-phone-key"))
        .andExpect(status().isForbidden());
    postSms("[]", "wrong-key").andExpect(status().isUnauthorized());
    postSms("[]", "test-phone-key").andExpect(status().isOk());
    mvc.perform(authed(get("/api/account/phone-key"))).andExpect(jsonPath("$.phoneKey").value("test-phone-key"));

    String newKey = JSON.readTree(mvc.perform(authed(post("/api/account/phone-key")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
        .get("phoneKey").asString();
    postSms("[]", "test-phone-key").andExpect(status().isUnauthorized());
    postSms("[]", newKey).andExpect(status().isOk());
    mvc.perform(authed(get("/api/account/phone-key"))).andExpect(jsonPath("$.phoneKey").value(newKey));
    mvc.perform(get("/api/account/phone-key").header("Authorization", "Bearer " + newKey))
        .andExpect(status().isForbidden());
  }

  @Test
  void signsInWithLoginIdAndSessionCookie() throws Exception {
    mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"login\": \"siddhant\", \"password\": \"nope\"}"))
        .andExpect(status().isUnauthorized());
    mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"login\": \"nobody\", \"password\": \"test-password\"}"))
        .andExpect(status().isUnauthorized());

    String setCookie = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"login\": \"  SIDDHANT \", \"password\": \"test-password\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Siddhant Anand"))
        .andExpect(jsonPath("$.admin").value(true))
        .andExpect(jsonPath("$.hasPhoneKey").value(true))
        .andReturn().getResponse().getHeader("Set-Cookie");
    assertThat(setCookie).contains("HttpOnly").contains("SameSite=Strict");
    var cookie = new Cookie("session", setCookie.replaceAll("^session=([^;]+).*", "$1"));

    mvc.perform(get("/api/auth/me").cookie(cookie)).andExpect(status().isOk());
    String tx = "{\"date\": \"2026-10-05\", \"amount\": 10, \"reason\": \"Chai\"}";
    mvc.perform(post("/api/transactions").cookie(cookie).contentType(MediaType.APPLICATION_JSON).content(tx))
        .andExpect(status().isForbidden());
    mvc.perform(as(cookie, post("/api/transactions")).contentType(MediaType.APPLICATION_JSON).content(tx))
        .andExpect(status().isCreated());

    mvc.perform(post("/api/auth/logout").cookie(cookie)).andExpect(status().isNoContent());
    mvc.perform(get("/api/auth/me").cookie(cookie)).andExpect(status().isUnauthorized());
  }

  // Uses an unknown login: the lockout outlives this test and would block the owner in the others.
  @Test
  void locksLoginAfterRepeatedFailures() throws Exception {
    String attempt = "{\"login\": \"guesser\", \"password\": \"wrong-password\"}";
    for (int i = 0; i < 5; i++) {
      mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(attempt))
          .andExpect(status().isUnauthorized());
    }
    mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(attempt))
        .andExpect(status().isTooManyRequests());
  }

  @Test
  void invitedUserSeesOnlyTheirOwnData() throws Exception {
    postTx("{\"date\": \"2026-10-05\", \"amount\": 99, \"reason\": \"Chai\"}");
    String ownerTx = jdbc.sql("SELECT id::text FROM transactions").query(String.class).single();

    String code = JSON.readTree(mvc.perform(authed(post("/api/account/invites")))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
        .get("code").asString();
    String signUp = JSON.writeValueAsString(
        Map.of("invite", code, "login", "Priya.K", "name", "Priya", "password", "priya-password"));

    mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(signUp.replace(code, "bad")))
        .andExpect(status().isForbidden());
    mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(signUp))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.login").value("priya.k"))
        .andExpect(jsonPath("$.admin").value(false))
        .andExpect(jsonPath("$.hasPhoneKey").value(false));
    mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(signUp))
        .andExpect(status().isForbidden());

    Cookie priya = signIn("priya.k", "priya-password");
    mvc.perform(as(priya, get("/api/transactions"))).andExpect(jsonPath("$.total").value(0));
    mvc.perform(as(priya, get("/api/summary?from=2026-10-01&to=2026-10-31"))).andExpect(jsonPath("$.count").value(0));
    mvc.perform(as(priya, patch("/api/transactions/" + ownerTx)).contentType(MediaType.APPLICATION_JSON)
        .content("{\"note\": \"x\"}")).andExpect(status().isNotFound());
    mvc.perform(as(priya, delete("/api/transactions/" + ownerTx))).andExpect(status().isNotFound());
    mvc.perform(as(priya, post("/api/account/invites"))).andExpect(status().isForbidden());
    mvc.perform(as(priya, get("/api/account/users"))).andExpect(status().isForbidden());
    mvc.perform(as(priya, get("/api/account/ai"))).andExpect(status().isForbidden());
    mvc.perform(as(priya, get("/api/account/phone-key"))).andExpect(jsonPath("$.phoneKey").isEmpty());

    mvc.perform(as(priya, post("/api/transactions")).contentType(MediaType.APPLICATION_JSON)
        .content("{\"date\": \"2026-10-05\", \"amount\": 99, \"reason\": \"Chai\"}")).andExpect(status().isCreated());
    mvc.perform(as(priya, get("/api/transactions"))).andExpect(jsonPath("$.total").value(1));
    mvc.perform(authed(get("/api/transactions"))).andExpect(jsonPath("$.total").value(1));
    mvc.perform(authed(get("/api/account/users"))).andExpect(jsonPath("$.users", hasSize(2)));
  }

  @Test
  void invitedUserCannotReachOwnerDataByUrlOrBody() throws Exception {
    // Owner: a transaction with a manual category, a review item, a weekly analysis.
    String ownerTx = firstInsertedId("{\"date\": \"2026-10-05\", \"amount\": 500, \"reason\": \"Gym fee\"}");
    patchTx(ownerTx, "{\"category\": \"Health & Medical\"}").andExpect(status().isOk());
    postSms("[{\"body\": \"Your account has been debited.\", \"received\": \"2026-10-05\"}]", "test-phone-key");
    long ownerReview = jdbc.sql("SELECT id FROM review").query(Long.class).single();
    mvc.perform(authed(post("/api/analysis/run?to=2026-10-11"))).andExpect(status().isCreated());
    long ownerId = jdbc.sql("SELECT id FROM users WHERE login = 'siddhant'").query(Long.class).single();

    String code = JSON.readTree(mvc.perform(authed(post("/api/account/invites")))
        .andReturn().getResponse().getContentAsString()).get("code").asString();
    mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(
        Map.of("invite", code, "login", "priya", "name", "Priya", "password", "priya-password"))));
    Cookie priya = signIn("priya", "priya-password");

    // Reading: nothing of the owner's shows up, whatever is put in the URL.
    for (String url : List.of("/api/transactions", "/api/transactions?userId=" + ownerId,
        "/api/transactions?user_id=" + ownerId + "&q=Gym", "/api/transactions?q=500",
        "/api/transactions?bank=Unknown bank&kind=spent")) {
      mvc.perform(as(priya, get(url))).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
    }
    mvc.perform(as(priya, get("/api/transactions").param("category", "Health & Medical")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
    mvc.perform(as(priya, get("/api/summary?from=2026-01-01&to=2026-12-31&userId=" + ownerId)))
        .andExpect(jsonPath("$.count").value(0)).andExpect(jsonPath("$.ownTransfers").value(0.0));
    mvc.perform(as(priya, get("/api/review"))).andExpect(jsonPath("$.items", empty()));
    mvc.perform(as(priya, get("/api/analysis/latest"))).andExpect(status().isNotFound());
    mvc.perform(as(priya, get("/api/analysis"))).andExpect(jsonPath("$.items", empty()));
    mvc.perform(as(priya, get("/api/auth/me"))).andExpect(jsonPath("$.login").value("priya"));

    // Writing to the owner's rows by id: always "not found", and nothing changes.
    mvc.perform(as(priya, post("/api/review/" + ownerReview + "/resolve")).contentType(MediaType.APPLICATION_JSON)
        .content("{\"date\": \"2026-10-05\", \"amount\": 1, \"reason\": \"x\"}")).andExpect(status().isNotFound());
    mvc.perform(as(priya, delete("/api/review/" + ownerReview))).andExpect(status().isNotFound());
    mvc.perform(as(priya, patch("/api/transactions/" + ownerTx)).contentType(MediaType.APPLICATION_JSON)
        .content("{\"category\": \"Travel\"}")).andExpect(status().isNotFound());

    // Claiming the owner's account in a body is ignored: the row is filed under whoever is signed in.
    mvc.perform(as(priya, post("/api/transactions")).contentType(MediaType.APPLICATION_JSON)
            .content("{\"date\": \"2026-10-04\", \"amount\": 1, \"reason\": \"x\", \"userId\": " + ownerId + "}"))
        .andExpect(status().isCreated());

    // Her own copy of the same merchant doesn't inherit the owner's manual category, and the
    // owner's identical payment isn't reported as a duplicate (which would reveal it exists).
    mvc.perform(as(priya, post("/api/transactions")).contentType(MediaType.APPLICATION_JSON)
            .content("{\"date\": \"2026-10-05\", \"amount\": 500, \"reason\": \"Gym fee\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.duplicateIndexes", empty()))
        .andExpect(jsonPath("$.inserted[0].categorySource").value(not("manual")));

    // Her phone key files SMS into her account only, and can't read anything.
    String priyaKey = JSON.readTree(mvc.perform(as(priya, post("/api/account/phone-key")))
        .andReturn().getResponse().getContentAsString()).get("phoneKey").asString();
    postSms("[{\"body\": \"Spent Rs.42 On HDFC Bank Card 4321 At CAFE On 2026-10-06:10:00:00\"}]", priyaKey)
        .andExpect(status().isCreated());
    mvc.perform(get("/api/transactions").header("Authorization", "Bearer " + priyaKey))
        .andExpect(status().isForbidden());
    mvc.perform(as(priya, get("/api/transactions"))).andExpect(jsonPath("$.total").value(3));

    mvc.perform(authed(get("/api/transactions")))
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].category").value("Health & Medical"));
    assertThat(jdbc.sql("SELECT COUNT(*) FROM review WHERE user_id = ?").param(ownerId).query(Integer.class).single())
        .isEqualTo(1);
  }

  @Test
  void exportsOnlyTheUsersOwnDataAndNoSecrets() throws Exception {
    postTx("{\"date\": \"2026-10-05\", \"amount\": 500, \"reason\": \"Gym fee\", \"note\": \"monthly\"}");
    postSms("[{\"body\": \"Your account has been debited.\", \"received\": \"2026-10-05\"}]", "test-phone-key");
    String code = JSON.readTree(mvc.perform(authed(post("/api/account/invites")))
        .andReturn().getResponse().getContentAsString()).get("code").asString();
    mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(
        Map.of("invite", code, "login", "priya", "name", "Priya", "password", "priya-password"))));
    Cookie priya = signIn("priya", "priya-password");
    mvc.perform(as(priya, post("/api/transactions")).contentType(MediaType.APPLICATION_JSON)
        .content("{\"date\": \"2026-10-06\", \"amount\": 77, \"reason\": \"Priya secret buy\"}"));

    String body = mvc.perform(authed(get("/api/account/export")))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Disposition", containsString("sanchiti-siddhant-")))
        .andExpect(jsonPath("$.app").value("Sanchiti"))
        .andExpect(jsonPath("$.account.login").value("siddhant"))
        .andExpect(jsonPath("$.counts.transactions").value(1))
        .andExpect(jsonPath("$.counts.review").value(1))
        .andExpect(jsonPath("$.data.transactions[0].note").value("monthly"))
        .andExpect(jsonPath("$.data.transactions[0].amount_minor").value(50000))
        .andExpect(jsonPath("$.data.reason_categories", hasSize(1)))
        .andReturn().getResponse().getContentAsString();
    assertThat(body).doesNotContain("Priya secret buy", "user_id", "password", "phone_key", "test-phone-key", "token_hash");

    mvc.perform(as(priya, get("/api/account/export")))
        .andExpect(jsonPath("$.counts.transactions").value(1))
        .andExpect(jsonPath("$.data.transactions[0].reason").value("Priya secret buy"))
        .andExpect(jsonPath("$.counts.review").value(0));
    mvc.perform(get("/api/account/export")).andExpect(status().isUnauthorized());
  }

  @Test
  void changesPasswordAndSignsOutOtherBrowsers() throws Exception {
    Cookie other = signIn("siddhant", "test-password");
    mvc.perform(authed(post("/api/account/password")).contentType(MediaType.APPLICATION_JSON)
            .content("{\"current\": \"wrong\", \"next\": \"new-password-1\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(authed(post("/api/account/password")).contentType(MediaType.APPLICATION_JSON)
            .content("{\"current\": \"test-password\", \"next\": \"short\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(authed(post("/api/account/password")).contentType(MediaType.APPLICATION_JSON)
            .content("{\"current\": \"test-password\", \"next\": \"new-password-1\"}"))
        .andExpect(status().isNoContent());

    mvc.perform(get("/api/auth/me").cookie(other)).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/auth/me").cookie(session)).andExpect(status().isOk());
    signIn("siddhant", "new-password-1");
  }

  @Test
  void geminiKeySettingNeverReturnsTheKeyAndRejectsJunk() throws Exception {
    String status = mvc.perform(authed(get("/api/account/ai")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.model").isNotEmpty())
        .andReturn().getResponse().getContentAsString();
    String hint = JSON.readTree(status).path("keyHint").asString("");
    assertThat(hint.isEmpty() || hint.length() == 5).isTrue();

    mvc.perform(authed(put("/api/account/ai")).contentType(MediaType.APPLICATION_JSON).content("{\"apiKey\": \"short\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(authed(put("/api/account/ai")).contentType(MediaType.APPLICATION_JSON)
            .content("{\"apiKey\": \"AIzaSyAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\", \"model\": \"../x\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("Invalid model name"));
  }
}
