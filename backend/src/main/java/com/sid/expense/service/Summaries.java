package com.sid.expense.service;

import com.sid.expense.domain.Banks;
import com.sid.expense.domain.Category;
import com.sid.expense.domain.Texts;
import com.sid.expense.domain.Transaction;
import com.sid.expense.domain.TransactionInput;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Deterministic spending maths; the AI never computes totals. */
public final class Summaries {

  public record Summary(
      LocalDate from,
      LocalDate to,
      String currency,
      double total,
      int count,
      double averagePerDay,
      /** SIPs, stocks etc.: money moved into investments, kept out of {@code total}. */
      double invested,
      int investedCount,
      /** Transfers to the user's own accounts and credit-card bill payments, kept out of {@code total}. */
      double ownTransfers,
      int ownTransferCount,
      /** Credits (salary, refunds, interest, money from others), excluding money from the user's own accounts. */
      double received,
      int receivedCount,
      /** Credits that came from the user's own accounts, kept out of {@code received}. */
      double ownTransfersIn,
      /** {@code received} minus spending and investments. */
      double net,
      List<CategoryTotal> byCategory,
      /** What came in, by credit category, excluding own transfers. */
      List<CategoryTotal> receivedByCategory,
      List<BankTotal> byBank,
      /** Everything debited per bank, including investments and own transfers. */
      List<BankDeductions> deductedByBank,
      /** Everything credited per bank, including money from the user's own accounts. */
      List<BankReceipts> creditedByBank,
      /** UPI / CARD / AUTOPAY / ... as sent or parsed; UNKNOWN when neither says. */
      List<MethodTotal> byPaymentMethod,
      List<DailyTotal> daily,
      List<Largest> largest,
      List<Largest> largestReceived,
      List<PayeeTotal> frequentPayees,
      int uncategorizedCount,
      /** Non-INR spends are reported separately; they are not converted or added to {@code total}. */
      List<CurrencyTotal> otherCurrencies) {}

  public record CategoryTotal(String category, double total, int count, double sharePct) {}

  public record BankTotal(String bank, double total, int count) {}

  public record BankDeductions(
      String bank, double total, int count, double spent, double invested, double ownTransfers) {}

  public record BankReceipts(String bank, double total, int count, double received, double ownTransfers) {}

  public record MethodTotal(String method, double total, int count) {}

  public record DailyTotal(LocalDate date, double total) {}

  public record Largest(LocalDate date, double amount, String payee, String category) {}

  public record PayeeTotal(String payee, int count, double total) {}

  public record CurrencyTotal(String currency, double total, int count) {}

  public record Comparison(
      double previousTotal, double totalChange, Double totalChangePct, List<CategoryChange> categoryChanges) {}

  public record CategoryChange(String category, double current, double previous, double change) {}

  private record Group(String label, long totalMinor, int count) {}

  private static final String UNKNOWN_BANK = Banks.UNKNOWN;

  private Summaries() {}

  public static Summary summarize(List<Transaction> all, LocalDate from, LocalDate to) {
    List<Transaction> txs = all.stream().filter(t -> !t.isCredit()).toList();
    List<Transaction> inr = txs.stream().filter(t -> TransactionInput.BASE_CURRENCY.equals(t.currency())).toList();
    List<Transaction> creditsInr = all.stream()
        .filter(t -> t.isCredit() && TransactionInput.BASE_CURRENCY.equals(t.currency()))
        .toList();
    List<Transaction> ownIn = creditsInr.stream().filter(Summaries::isOwnTransfer).toList();
    List<Transaction> receivedRows = creditsInr.stream().filter(t -> !isOwnTransfer(t)).toList();
    long receivedMinor = receivedRows.stream().mapToLong(Transaction::amountMinor).sum();
    List<Transaction> investments = inr.stream().filter(Summaries::isInvestment).toList();
    List<Transaction> own = inr.stream().filter(Transaction::isOwnTransfer).toList();
    List<Transaction> base = inr.stream()
        .filter(t -> !t.isInvestment() && !t.isOwnTransfer())
        .toList();
    long totalMinor = base.stream().mapToLong(Transaction::amountMinor).sum();
    int days = (int) Math.max(1, ChronoUnit.DAYS.between(from, to) + 1);

    Map<String, Long> perDay = new HashMap<>();
    for (Group g : group(base, t -> t.date().toString())) perDay.put(g.label(), g.totalMinor());
    List<DailyTotal> daily = new ArrayList<>(days);
    for (int i = 0; i < days; i++) {
      LocalDate d = from.plusDays(i);
      daily.add(new DailyTotal(d, major(perDay.getOrDefault(d.toString(), 0L))));
    }

    Map<String, String> firstPayee = new LinkedHashMap<>();
    for (Transaction t : base) firstPayee.putIfAbsent(Texts.normalizeReason(t.payee()), t.payee());
    long investedMinor = investments.stream().mapToLong(Transaction::amountMinor).sum();

    return new Summary(
        from,
        to,
        TransactionInput.BASE_CURRENCY,
        major(totalMinor),
        base.size(),
        Math.round((double) totalMinor / days) / 100.0,
        major(investedMinor),
        investments.size(),
        major(own.stream().mapToLong(Transaction::amountMinor).sum()),
        own.size(),
        major(receivedMinor),
        receivedRows.size(),
        major(ownIn.stream().mapToLong(Transaction::amountMinor).sum()),
        major(receivedMinor - totalMinor - investedMinor),
        group(base, t -> t.category() == null ? Category.UNCATEGORIZED : t.category()).stream()
            .map(g -> new CategoryTotal(g.label(), major(g.totalMinor()), g.count(),
                totalMinor == 0 ? 0 : round1(g.totalMinor() * 100.0 / totalMinor)))
            .toList(),
        group(receivedRows, t -> t.category() == null ? Category.RECEIVED.label() : t.category()).stream()
            .map(g -> new CategoryTotal(g.label(), major(g.totalMinor()), g.count(),
                receivedMinor == 0 ? 0 : round1(g.totalMinor() * 100.0 / receivedMinor)))
            .toList(),
        group(base.stream().filter(t -> t.bank() != null).toList(), Transaction::bank).stream()
            .map(g -> new BankTotal(g.label(), major(g.totalMinor()), g.count()))
            .toList(),
        deductedByBank(inr),
        creditedByBank(creditsInr),
        group(base, t -> t.paymentMethod() == null ? "UNKNOWN" : t.paymentMethod()).stream()
            .map(g -> new MethodTotal(g.label(), major(g.totalMinor()), g.count()))
            .toList(),
        daily,
        largest(base),
        largest(receivedRows),
        group(base, t -> Texts.normalizeReason(t.payee())).stream()
            .filter(g -> g.count() > 1)
            .sorted(Comparator.comparingInt(Group::count).reversed()
                .thenComparing(Comparator.comparingLong(Group::totalMinor).reversed()))
            .limit(5)
            .map(g -> new PayeeTotal(firstPayee.getOrDefault(g.label(), g.label()), g.count(), major(g.totalMinor())))
            .toList(),
        (int) base.stream().filter(t -> t.category() == null).count(),
        group(txs.stream().filter(t -> !TransactionInput.BASE_CURRENCY.equals(t.currency())).toList(),
                Transaction::currency).stream()
            .map(g -> new CurrencyTotal(g.label(), major(g.totalMinor()), g.count()))
            .toList());
  }

  public static Comparison compare(Summary current, Summary previous) {
    Map<String, Double> prev = new LinkedHashMap<>();
    previous.byCategory().forEach(c -> prev.put(c.category(), c.total()));
    Map<String, Double> cur = new LinkedHashMap<>();
    current.byCategory().forEach(c -> cur.put(c.category(), c.total()));
    Set<String> categories = new LinkedHashSet<>(prev.keySet());
    categories.addAll(cur.keySet());

    List<CategoryChange> changes = categories.stream()
        .map(c -> {
          double now = cur.getOrDefault(c, 0.0);
          double before = prev.getOrDefault(c, 0.0);
          return new CategoryChange(c, now, before, major(minor(now) - minor(before)));
        })
        .sorted(Comparator.comparingDouble((CategoryChange c) -> Math.abs(c.change())).reversed())
        .limit(6)
        .toList();

    return new Comparison(
        previous.total(),
        major(minor(current.total()) - minor(previous.total())),
        previous.total() == 0 ? null : round1((current.total() - previous.total()) / previous.total() * 100),
        changes);
  }

  private static List<Largest> largest(List<Transaction> rows) {
    return rows.stream()
        .sorted(Comparator.comparingLong(Transaction::amountMinor).reversed())
        .limit(5)
        .map(t -> new Largest(t.date(), t.amount(), t.payee(), t.category()))
        .toList();
  }

  private static List<BankReceipts> creditedByBank(List<Transaction> credits) {
    return group(credits, t -> t.bank() == null ? UNKNOWN_BANK : t.bank()).stream()
        .map(g -> {
          long own = credits.stream()
              .filter(t -> g.label().equals(t.bank() == null ? UNKNOWN_BANK : t.bank()) && isOwnTransfer(t))
              .mapToLong(Transaction::amountMinor)
              .sum();
          return new BankReceipts(g.label(), major(g.totalMinor()), g.count(), major(g.totalMinor() - own), major(own));
        })
        .toList();
  }

  private static boolean isOwnTransfer(Transaction t) {
    return t.isOwnTransfer();
  }

  private static List<BankDeductions> deductedByBank(List<Transaction> inr) {
    return group(inr, t -> t.bank() == null ? UNKNOWN_BANK : t.bank()).stream()
        .map(g -> {
          List<Transaction> rows = inr.stream()
              .filter(t -> g.label().equals(t.bank() == null ? UNKNOWN_BANK : t.bank()))
              .toList();
          long invested = rows.stream().filter(Summaries::isInvestment).mapToLong(Transaction::amountMinor).sum();
          long own = rows.stream()
              .filter(Transaction::isOwnTransfer)
              .mapToLong(Transaction::amountMinor)
              .sum();
          return new BankDeductions(g.label(), major(g.totalMinor()), g.count(),
              major(g.totalMinor() - invested - own), major(invested), major(own));
        })
        .toList();
  }

  private static List<Group> group(List<Transaction> txs, Function<Transaction, String> key) {
    Map<String, long[]> groups = new LinkedHashMap<>();
    for (Transaction t : txs) {
      long[] g = groups.computeIfAbsent(key.apply(t), k -> new long[2]);
      g[0] += t.amountMinor();
      g[1] += 1;
    }
    return groups.entrySet().stream()
        .map(e -> new Group(e.getKey(), e.getValue()[0], (int) e.getValue()[1]))
        .sorted(Comparator.comparingLong(Group::totalMinor).reversed())
        .toList();
  }

  private static boolean isInvestment(Transaction t) {
    return t.isInvestment();
  }

  private static long minor(double v) {
    return Math.round(v * 100);
  }

  private static double major(long minor) {
    return minor / 100.0;
  }

  private static double round1(double v) {
    return Math.round(v * 10) / 10.0;
  }
}
