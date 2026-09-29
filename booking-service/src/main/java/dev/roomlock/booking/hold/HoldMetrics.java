package dev.roomlock.booking.hold;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * The hold flow as Grafana sees it: what a strategy answered, how often it had to try again and how
 * long one attempt took, all tagged with the strategy that did the work (D2).
 *
 * <p>Every strategy funnels through {@link TransactionalHoldStrategy}, so this is the only place
 * that records: one timer sample per attempt, one retry per attempt that follows a lost race, one
 * outcome per hold.
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

  HoldMetrics(MeterRegistry registry) {
    // no meter is registered yet
  }

  /** Times one attempt of the strategy and returns what it answered. */
  HoldOutcome timed(LockingStrategy strategy, Supplier<HoldOutcome> attempt) {
    throw new UnsupportedOperationException("Hold attempts are not timed yet");
  }

  /** Counts an attempt that runs only because the previous one lost the race. */
  void retried(LockingStrategy strategy) {
    throw new UnsupportedOperationException("Hold retries are not counted yet");
  }

  /** Counts the answer the caller of {@link HoldStrategy#hold} gets, once per hold. */
  void answered(LockingStrategy strategy, HoldOutcome outcome) {
    throw new UnsupportedOperationException("Hold outcomes are not counted yet");
  }
}
