package com.sid.expense.web;

import com.sid.expense.ai.AiClient;
import com.sid.expense.config.AppProperties;
import com.sid.expense.domain.Banks;
import com.sid.expense.domain.Category;
import com.sid.expense.domain.Dates;
import com.sid.expense.domain.Dates.Period;
import com.sid.expense.repo.AnalysisRepository;
import com.sid.expense.repo.AnalysisRepository.StoredAnalysis;
import com.sid.expense.repo.TransactionRepository;
import com.sid.expense.service.AnalysisService;
import com.sid.expense.service.AnalysisService.WeeklyResult;
import com.sid.expense.service.Categorizer;
import com.sid.expense.service.Summaries;
import com.sid.expense.service.Summaries.Summary;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InsightsController {

  record Health(boolean ok, boolean ai) {}

  record Categories(List<String> categories) {}

  record BankList(List<String> banks) {}

  record CategorizeResult(int categorized, int pending) {}

  record AnalysisList(List<StoredAnalysis> items) {}

  private final AppProperties props;
  private final AiClient ai;
  private final TransactionRepository transactions;
  private final AnalysisRepository analyses;
  private final Categorizer categorizer;
  private final AnalysisService analysis;

  public InsightsController(
      AppProperties props, AiClient ai, TransactionRepository transactions, AnalysisRepository analyses,
      Categorizer categorizer, AnalysisService analysis) {
    this.props = props;
    this.ai = ai;
    this.transactions = transactions;
    this.analyses = analyses;
    this.categorizer = categorizer;
    this.analysis = analysis;
  }

  @GetMapping("/health")
  public Health health() {
    return new Health(true, ai.isEnabled());
  }

  @GetMapping("/api/categories")
  public Categories categories() {
    return new Categories(Category.labels());
  }

  @GetMapping("/api/banks")
  public BankList banks() {
    return new BankList(new Banks(props.knownBanks()).canonicalNames());
  }

  @GetMapping("/api/summary")
  public Summary summary(
      @RequestAttribute(AuthUser.ATTRIBUTE) AuthUser user, @RequestParam(required = false) String from,
      @RequestParam(required = false) String to) {
    LocalDate end = to == null ? LocalDate.now(props.zone()) : TransactionController.date(to, "to");
    LocalDate start = from == null ? end.withDayOfMonth(1) : TransactionController.date(from, "from");
    if (start.isAfter(end)) throw new ApiException(HttpStatus.BAD_REQUEST, "`from` must be on or before `to`");
    if (start.plusYears(5).isBefore(end)) throw new ApiException(HttpStatus.BAD_REQUEST, "Range is limited to 5 years");
    return Summaries.summarize(transactions.inRange(user.id(), start, end), start, end);
  }

  @PostMapping("/api/categorize")
  public CategorizeResult categorize(@RequestAttribute(AuthUser.ATTRIBUTE) AuthUser user) {
    requireAi();
    return new CategorizeResult(categorizer.categorizePending(user.id()), transactions.pendingCount(user.id()));
  }

  /** Defaults to the last completed Mon–Sun week; {@code to} analyses the 7 days ending on that date. */
  @PostMapping("/api/analysis/run")
  public ResponseEntity<WeeklyResult> run(
      @RequestAttribute(AuthUser.ATTRIBUTE) AuthUser user, @RequestParam(required = false) String to,
      @RequestParam(defaultValue = "false") boolean force) {
    requireAi();
    Period period = to == null
        ? Dates.lastCompletedWeek(LocalDate.now(props.zone()))
        : Dates.weekEnding(TransactionController.date(to, "to"));
    WeeklyResult result = analysis.runWeekly(user.id(), period, force);
    return ResponseEntity.status("created".equals(result.status()) ? HttpStatus.CREATED : HttpStatus.OK).body(result);
  }

  @GetMapping("/api/analysis/latest")
  public StoredAnalysis latest(@RequestAttribute(AuthUser.ATTRIBUTE) AuthUser user) {
    return analyses.latest(user.id()).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No analysis yet"));
  }

  @GetMapping("/api/analysis")
  public AnalysisList history(
      @RequestAttribute(AuthUser.ATTRIBUTE) AuthUser user, @RequestParam(defaultValue = "12") int limit) {
    return new AnalysisList(analyses.list(user.id(), Math.clamp(limit, 1, 52)));
  }

  private void requireAi() {
    if (!ai.isEnabled()) {
      throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Gemini is not set up. An admin can add an API key in Settings.");
    }
  }
}
