package com.sid.expense.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sid.expense.ai.AiClient;
import com.sid.expense.ai.AiClient.AiResult;
import com.sid.expense.ai.AiException;
import com.sid.expense.domain.Dates.Period;
import com.sid.expense.repo.AnalysisRepository;
import com.sid.expense.repo.AnalysisRepository.StoredAnalysis;
import com.sid.expense.repo.TransactionRepository;
import com.sid.expense.service.Summaries.Summary;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public class AnalysisService {

  private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);

  private static final String SYSTEM = """
      You are a personal finance assistant for a person in India.
      You receive pre-computed spending statistics for one week as JSON. Amounts are in INR unless stated.
      Rules:
      - Never recalculate totals; quote numbers exactly as given, formatted like ₹1,234.
      - "total" is spending only; "invested" (SIPs, stocks, mutual funds) is saving, not spending, and
        "ownTransfers" (to the person's own accounts, credit-card bill payments) is neither.
      - Be specific, concise and non-judgemental. Refer to actual categories and payees from the data.
      - Return at most 3 items in every list. Saving tips must be practical and tied to this data.
      - "unusualSpends" only lists items that look out of pattern; return an empty list if none.""";

  private static final Map<String, Object> STRING = Map.of("type", "STRING");
  private static final Map<String, Object> STRING_LIST = Map.of("type", "ARRAY", "items", STRING);
  private static final Map<String, Object> RESPONSE_SCHEMA = Map.of(
      "type", "OBJECT",
      "properties", Map.of(
          "headline", STRING,
          "summary", STRING,
          "highlights", STRING_LIST,
          "concerns", STRING_LIST,
          "savingTips", STRING_LIST,
          "unusualSpends", Map.of(
              "type", "ARRAY",
              "items", Map.of(
                  "type", "OBJECT",
                  "properties", Map.of("reason", STRING, "amount", Map.of("type", "NUMBER"), "why", STRING),
                  "required", List.of("reason", "amount", "why")))),
      "required", List.of("headline", "summary", "highlights", "concerns", "savingTips", "unusualSpends"));

  public record Insights(
      String headline,
      String summary,
      List<String> highlights,
      List<String> concerns,
      List<String> savingTips,
      List<UnusualSpend> unusualSpends) {}

  public record UnusualSpend(String reason, double amount, String why) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record WeeklyResult(String status, StoredAnalysis analysis, String reason, LocalDate from, LocalDate to) {
    static WeeklyResult of(String status, StoredAnalysis analysis) {
      return new WeeklyResult(status, analysis, null, null, null);
    }
  }

  private final TransactionRepository transactions;
  private final AnalysisRepository analyses;
  private final Categorizer categorizer;
  private final AiClient ai;
  private final JsonMapper json;

  public AnalysisService(
      TransactionRepository transactions, AnalysisRepository analyses, Categorizer categorizer, AiClient ai,
      JsonMapper json) {
    this.transactions = transactions;
    this.analyses = analyses;
    this.categorizer = categorizer;
    this.ai = ai;
    this.json = json;
  }

  public WeeklyResult runWeekly(long userId, Period period, boolean force) {
    LocalDate from = period.from();
    LocalDate to = period.to();

    try {
      categorizer.categorizePending(userId);
    } catch (AiException e) {
      log.warn("[analysis] categorisation skipped: {}", e.getMessage());
    }

    Summary current = Summaries.summarize(transactions.inRange(userId, from, to), from, to);
    if (current.count() == 0 && current.investedCount() == 0 && current.ownTransferCount() == 0
        && current.otherCurrencies().isEmpty()) {
      return new WeeklyResult("skipped", null, "No transactions in this period", from, to);
    }

    LocalDate prevTo = from.minusDays(1);
    LocalDate prevFrom = prevTo.minusDays(6);
    Summary previous = Summaries.summarize(transactions.inRange(userId, prevFrom, prevTo), prevFrom, prevTo);
    LocalDate monthFrom = to.withDayOfMonth(1);
    Summary monthToDate = Summaries.summarize(transactions.inRange(userId, monthFrom, to), monthFrom, to);

    @SuppressWarnings("unchecked")
    Map<String, Object> week = json.readValue(json.writeValueAsString(current), LinkedHashMap.class);
    week.put("daily", current.daily().stream().map(Summaries.DailyTotal::total).toList());

    Map<String, Object> stats = new LinkedHashMap<>();
    stats.put("week", week);
    stats.put("vsPreviousWeek", Summaries.compare(current, previous));
    stats.put("monthToDate", Map.of(
        "from", monthFrom.toString(), "to", to.toString(), "total", monthToDate.total(), "count", monthToDate.count()));

    String statsJson = json.writeValueAsString(stats);
    String inputHash = TransactionService.sha256(statsJson);
    Optional<StoredAnalysis> latest = analyses.latest(userId);
    if (!force && latest.isPresent() && latest.get().inputHash().equals(inputHash)) {
      return WeeklyResult.of("cached", latest.get());
    }

    AiResult result = ai.generate(SYSTEM, statsJson, RESPONSE_SCHEMA);
    Insights insights = toInsights(result.data());
    StoredAnalysis saved = analyses.save(userId, from, to, result.model(), inputHash, stats, insights, result.usage());
    return WeeklyResult.of("created", saved);
  }

  private static Insights toInsights(Object data) {
    if (data instanceof Map<?, ?> m
        && m.get("headline") instanceof String headline
        && m.get("summary") instanceof String summary) {
      List<UnusualSpend> unusual = list(m.get("unusualSpends")).stream()
          .filter(u -> u instanceof Map<?, ?>)
          .map(u -> (Map<?, ?>) u)
          .filter(u -> u.get("reason") instanceof String && u.get("amount") instanceof Number && u.get("why") instanceof String)
          .limit(5)
          .map(u -> new UnusualSpend((String) u.get("reason"), ((Number) u.get("amount")).doubleValue(), (String) u.get("why")))
          .toList();
      return new Insights(headline, summary, strings(m.get("highlights")), strings(m.get("concerns")),
          strings(m.get("savingTips")), unusual);
    }
    throw new AiException("Gemini returned an unexpected analysis format");
  }

  private static List<?> list(Object v) {
    return v instanceof List<?> l ? l : List.of();
  }

  private static List<String> strings(Object v) {
    return list(v).stream().filter(String.class::isInstance).map(String.class::cast).limit(5).toList();
  }
}
