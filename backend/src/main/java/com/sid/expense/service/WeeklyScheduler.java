package com.sid.expense.service;

import com.sid.expense.ai.AiClient;
import com.sid.expense.config.AppProperties;
import com.sid.expense.domain.Dates;
import com.sid.expense.domain.Dates.Period;
import com.sid.expense.repo.AnalysisRepository;
import com.sid.expense.repo.TransactionRepository;
import com.sid.expense.repo.UserRepository;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** While the server runs, retries pending categorisation and produces one analysis per completed week, per user. */
@Component
public class WeeklyScheduler {

  private static final Logger log = LoggerFactory.getLogger(WeeklyScheduler.class);

  private final AppProperties props;
  private final AiClient ai;
  private final UserRepository users;
  private final TransactionRepository transactions;
  private final AnalysisRepository analyses;
  private final Categorizer categorizer;
  private final AnalysisService analysis;

  public WeeklyScheduler(
      AppProperties props, AiClient ai, UserRepository users, TransactionRepository transactions,
      AnalysisRepository analyses, Categorizer categorizer, AnalysisService analysis) {
    this.props = props;
    this.ai = ai;
    this.users = users;
    this.transactions = transactions;
    this.analyses = analyses;
    this.categorizer = categorizer;
    this.analysis = analysis;
  }

  @Scheduled(initialDelay = 5_000, fixedDelay = 3_600_000)
  public void tick() {
    if (!props.autoWeekly() || !ai.isEnabled()) return;
    Period week = Dates.lastCompletedWeek(LocalDate.now(props.zone()));
    for (long userId : users.ids()) {
      try {
        if (transactions.pendingCount(userId) > 0) categorizer.categorizePending(userId);
        boolean done = analyses.latest(userId).map(a -> !a.periodTo().isBefore(week.to())).orElse(false);
        if (done) continue;
        var result = analysis.runWeekly(userId, week, false);
        log.info("[weekly] user {}: {} for {}..{}", userId, result.status(), week.from(), week.to());
      } catch (RuntimeException e) {
        log.warn("[weekly] user {}: will retry next hour: {}", userId, e.getMessage());
      }
    }
  }
}
