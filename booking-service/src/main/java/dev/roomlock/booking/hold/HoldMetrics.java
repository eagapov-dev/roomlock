package dev.roomlock.booking.hold;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * The hold flow as Grafana sees it: what a strategy answered, how often it had to try again and how
 * long one attempt took, all tagged with the strategy that did the work (D2).
 *
 * <p>Every strategy funnels through {@link TransactionalHoldStrategy}, so this is the only place
 * that records: one timer sample per attempt, one retry per attempt that follows a lost race, one
 * outcome per hold.
 *
 * <p>The meters of every strategy are registered up front, not on first use, so a panel has a
 * series to draw from the first scrape instead of a gap until the first hold of that kind.
 */
@Component
class HoldMetrics {

  /** Holds by strategy and outcome; the rate of this counter is the hold throughput. */
  static final String OUTCOMES = "roomlock.hold.outcomes";

  /** Attempts that ran only because the previous one lost the race (OPTIMISTIC, rule DB-8). */
  static final String RETRIES = "roomlock.hold.retries";

  /** Duration of one attempt, with a histogram so p95/p99 can be read per strategy. */
  static final String ATTEMPTS = "roomlock.hold.attempts";

  static final String STRATEGY_TAG = "strategy";
  static final String OUTCOME_TAG = "outcome";

  private final Map<LockingStrategy, Timer> attempts;
  private final Map<LockingStrategy, Counter> retries;
  private final Map<LockingStrategy, Map<Outcome, Counter>> outcomes;

  HoldMetrics(MeterRegistry registry) {
    this.attempts = perStrategy(strategy -> attemptTimer(registry, strategy));
    this.retries = perStrategy(strategy -> retryCounter(registry, strategy));
    this.outcomes = perStrategy(strategy -> outcomeCounters(registry, strategy));
  }

  /**
   * Times one attempt of the strategy and returns what it answered. A sample is recorded even when
   * the attempt throws, so a failing attempt cannot hide from the latency panel.
   */
  HoldOutcome timed(LockingStrategy strategy, Supplier<HoldOutcome> attempt) {
    return attempts.get(strategy).record(attempt);
  }

  /** Counts an attempt that runs only because the previous one lost the race. */
  void retried(LockingStrategy strategy) {
    retries.get(strategy).increment();
  }

  /** Counts the answer the caller of {@link HoldStrategy#hold} gets, once per hold. */
  void answered(LockingStrategy strategy, HoldOutcome outcome) {
    outcomes.get(strategy).get(Outcome.of(outcome)).increment();
  }

  private static Timer attemptTimer(MeterRegistry registry, LockingStrategy strategy) {
    return Timer.builder(ATTEMPTS)
        .description("Duration of one attempt to hold a room")
        .tag(STRATEGY_TAG, tagOf(strategy))
        .publishPercentileHistogram()
        .register(registry);
  }

  private static Counter retryCounter(MeterRegistry registry, LockingStrategy strategy) {
    return Counter.builder(RETRIES)
        .description("Hold attempts that retry a lost race")
        .tag(STRATEGY_TAG, tagOf(strategy))
        .register(registry);
  }

  private static Map<Outcome, Counter> outcomeCounters(
      MeterRegistry registry, LockingStrategy strategy) {
    return Arrays.stream(Outcome.values())
        .collect(
            Collectors.toUnmodifiableMap(
                Function.identity(), outcome -> outcomeCounter(registry, strategy, outcome)));
  }

  private static Counter outcomeCounter(
      MeterRegistry registry, LockingStrategy strategy, Outcome outcome) {
    return Counter.builder(OUTCOMES)
        .description("Holds by the outcome their strategy answered with")
        .tag(STRATEGY_TAG, tagOf(strategy))
        .tag(OUTCOME_TAG, outcome.tag())
        .register(registry);
  }

  private static <T> Map<LockingStrategy, T> perStrategy(Function<LockingStrategy, T> meter) {
    return Arrays.stream(LockingStrategy.values())
        .collect(Collectors.toUnmodifiableMap(Function.identity(), meter));
  }

  /** The spelling {@code roomlock.locking.strategy} uses in configuration. */
  private static String tagOf(LockingStrategy strategy) {
    return strategy.name().toLowerCase(Locale.ROOT);
  }

  /** Tag values of {@link HoldOutcome}, so the tag never carries a Java type name. */
  private enum Outcome {
    INSERTED,
    BUSY,
    CONTENDED,
    KEY_TAKEN;

    static Outcome of(HoldOutcome outcome) {
      return switch (outcome) {
        case HoldOutcome.Inserted inserted -> INSERTED;
        case HoldOutcome.Busy busy -> BUSY;
        case HoldOutcome.Contended contended -> CONTENDED;
        case HoldOutcome.KeyTaken keyTaken -> KEY_TAKEN;
      };
    }

    String tag() {
      return name().toLowerCase(Locale.ROOT);
    }
  }
}
