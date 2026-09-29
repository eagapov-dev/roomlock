package dev.roomlock.booking.hold;

import static dev.roomlock.booking.support.TestData.aHold;
import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.lang.Nullable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * What one hold records, with the attempts scripted instead of raced: a race proves the strategies
 * (see {@code HoldStrategyConcurrencyTest}), a script proves the counting (D2).
 *
 * <p>The metric names are spelled out here on purpose: the Grafana dashboard queries exactly these,
 * so a rename has to break this test.
 */
class HoldMetricsTest {

  private static final String OUTCOMES = "roomlock.hold.outcomes";
  private static final String RETRIES = "roomlock.hold.retries";
  private static final String ATTEMPTS = "roomlock.hold.attempts";
  private static final String STRATEGY = "strategy";
  private static final String OUTCOME = "outcome";

  private final MeterRegistry registry = new SimpleMeterRegistry();
  private final HoldMetrics metrics = new HoldMetrics(registry);
  private final HoldAttempt attempt = aHold().attempt();

  @ParameterizedTest
  @EnumSource(LockingStrategy.class)
  void shouldCountInsertedAndTimeTheAttemptWhenFirstAttemptWins(LockingStrategy strategy) {
    // given
    HoldStrategy holds = scripted(strategy, inserted());

    // when
    holds.hold(attempt);

    // then
    assertThat(outcomes(strategy, "inserted")).isEqualTo(1);
    assertThat(retries(strategy)).isZero();
    assertThat(attempts(strategy).count()).isEqualTo(1);
  }

  @Test
  void shouldTagBusyContendedAndKeyTakenWithTheirOwnOutcomeWhenStrategyAnswersThem() {
    // given
    LockingStrategy strategy = LockingStrategy.CONSTRAINT;

    // when
    scripted(strategy, new HoldOutcome.Busy()).hold(attempt);
    scripted(strategy, new HoldOutcome.KeyTaken()).hold(attempt);
    scripted(strategy, new HoldOutcome.Contended()).hold(attempt);

    // then
    assertThat(outcomes(strategy, "busy")).isEqualTo(1);
    assertThat(outcomes(strategy, "key_taken")).isEqualTo(1);
    assertThat(outcomes(strategy, "contended")).isEqualTo(1);
    assertThat(outcomes(strategy, "inserted")).isZero();
  }

  @Test
  void shouldCountOneRetryPerAttemptAfterALostRaceWhenHoldFinallyWins() {
    // given three attempts, the first two of which lose the version bump (rule DB-8)
    LockingStrategy strategy = LockingStrategy.OPTIMISTIC;
    HoldStrategy holds =
        scripted(strategy, new HoldOutcome.Contended(), new HoldOutcome.Contended(), inserted());

    // when
    HoldOutcome outcome = holds.hold(attempt);

    // then
    assertThat(outcome).isInstanceOf(HoldOutcome.Inserted.class);
    assertThat(retries(strategy)).isEqualTo(2);
    assertThat(attempts(strategy).count()).isEqualTo(3);
    assertThat(outcomes(strategy, "inserted")).isEqualTo(1);
    assertThat(outcomes(strategy, "contended")).isZero();
  }

  @Test
  void shouldCountContendedOnceWhenEveryAttemptLosesTheRace() {
    // given
    LockingStrategy strategy = LockingStrategy.OPTIMISTIC;
    HoldStrategy holds =
        scripted(
            strategy,
            new HoldOutcome.Contended(),
            new HoldOutcome.Contended(),
            new HoldOutcome.Contended());

    // when
    HoldOutcome outcome = holds.hold(attempt);

    // then
    assertThat(outcome).isInstanceOf(HoldOutcome.Contended.class);
    assertThat(retries(strategy)).isEqualTo(2);
    assertThat(attempts(strategy).count()).isEqualTo(3);
    assertThat(outcomes(strategy, "contended")).isEqualTo(1);
  }

  @Test
  void shouldTagEveryStrategyTheWayConfigurationSpellsItWhenMetricsAreRegistered() {
    // given
    // when
    // then
    assertThat(registry.get(ATTEMPTS).tag(STRATEGY, "pessimistic").timer()).isNotNull();
    assertThat(registry.get(ATTEMPTS).tag(STRATEGY, "optimistic").timer()).isNotNull();
    assertThat(registry.get(ATTEMPTS).tag(STRATEGY, "constraint").timer()).isNotNull();
  }

  private HoldOutcome inserted() {
    return new HoldOutcome.Inserted(attempt.newBooking());
  }

  private HoldStrategy scripted(LockingStrategy strategy, HoldOutcome... outcomes) {
    return new ScriptedStrategy(metrics, strategy, List.of(outcomes));
  }

  private double outcomes(LockingStrategy strategy, String outcome) {
    return registry
        .get(OUTCOMES)
        .tag(STRATEGY, tagOf(strategy))
        .tag(OUTCOME, outcome)
        .counter()
        .count();
  }

  private double retries(LockingStrategy strategy) {
    return registry.get(RETRIES).tag(STRATEGY, tagOf(strategy)).counter().count();
  }

  private Timer attempts(LockingStrategy strategy) {
    return registry.get(ATTEMPTS).tag(STRATEGY, tagOf(strategy)).timer();
  }

  private static String tagOf(LockingStrategy strategy) {
    return strategy.name().toLowerCase(Locale.ROOT);
  }

  /** A strategy whose attempts answer from a script, so the retry loop is not left to a race. */
  private static final class ScriptedStrategy extends TransactionalHoldStrategy {

    private final LockingStrategy strategy;
    private final Deque<HoldOutcome> scripted;

    ScriptedStrategy(HoldMetrics metrics, LockingStrategy strategy, List<HoldOutcome> outcomes) {
      super(new NoTransactions(), metrics, outcomes.size());
      this.strategy = strategy;
      this.scripted = new ArrayDeque<>(outcomes);
    }

    @Override
    public LockingStrategy strategy() {
      return strategy;
    }

    @Override
    protected HoldOutcome write(HoldAttempt held) {
      return scripted.removeFirst();
    }
  }

  /** A strategy opens a transaction per attempt; a scripted attempt has no resource to enlist. */
  private static final class NoTransactions implements PlatformTransactionManager {

    @Override
    public TransactionStatus getTransaction(@Nullable TransactionDefinition definition) {
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {
      // nothing was written
    }

    @Override
    public void rollback(TransactionStatus status) {
      // nothing was written
    }
  }
}
