package dev.roomlock.booking.hold;

import static dev.roomlock.booking.support.TestData.aHold;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import dev.roomlock.booking.support.NoTransactions;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.PessimisticLockingFailureException;

/**
 * What a strategy answers when the database itself declares the attempt the loser of a race: eight
 * concurrent inserts can deadlock while PostgreSQL checks {@code booking_no_overlap}, and a hold
 * has to survive that as a retryable answer instead of a failed request (D2).
 *
 * <p>The failures are scripted rather than raced: a deadlock cannot be provoked on demand, and the
 * translation is what needs pinning.
 */
class HoldConcurrencyFailureTest {

  private static final String DEADLOCK = "40P01";
  private static final String SERIALIZATION_FAILURE = "40001";

  private final HoldMetrics metrics = new HoldMetrics(new SimpleMeterRegistry());
  private final HoldAttempt attempt = aHold().attempt();

  @Test
  void shouldAnswerContendedWhenAttemptDeadlocks() {
    // given
    HoldStrategy holds = scripted(deadlock());

    // when
    HoldOutcome outcome = holds.hold(attempt);

    // then
    assertThat(outcome).isInstanceOf(HoldOutcome.Contended.class);
  }

  @Test
  void shouldAnswerContendedWhenTransactionCannotBeSerialized() {
    // given
    HoldStrategy holds = scripted(serializationFailure());

    // when
    HoldOutcome outcome = holds.hold(attempt);

    // then
    assertThat(outcome).isInstanceOf(HoldOutcome.Contended.class);
  }

  @Test
  void shouldInsertOnTheNextAttemptWhenTheFirstOneDeadlocks() {
    // given a strategy that may try again, as OPTIMISTIC does
    HoldStrategy holds = scripted(deadlock(), inserted());

    // when
    HoldOutcome outcome = holds.hold(attempt);

    // then
    assertThat(outcome).isInstanceOf(HoldOutcome.Inserted.class);
  }

  @Test
  void shouldFailWhenTheDatabaseFailureIsNotALostRace() {
    // given
    HoldStrategy holds = scripted(new DataAccessResourceFailureException("connection refused"));

    // when
    // then
    assertThatExceptionOfType(DataAccessResourceFailureException.class)
        .isThrownBy(() -> holds.hold(attempt));
  }

  private HoldOutcome inserted() {
    return new HoldOutcome.Inserted(attempt.newBooking());
  }

  /** What Spring makes of SQL state 40P01, the deadlock PostgreSQL reports to the loser. */
  private static RuntimeException deadlock() {
    return new PessimisticLockingFailureException(
        "deadlock detected", new SQLException("deadlock detected", DEADLOCK));
  }

  /** What Spring makes of SQL state 40001. */
  private static RuntimeException serializationFailure() {
    return new CannotAcquireLockException(
        "could not serialize access",
        new SQLException("could not serialize access", SERIALIZATION_FAILURE));
  }

  /** A strategy whose attempts answer, or throw, from a script. */
  private HoldStrategy scripted(Object... script) {
    return new ScriptedStrategy(metrics, List.of(script));
  }

  private static final class ScriptedStrategy extends TransactionalHoldStrategy {

    private final Deque<Object> scripted;

    ScriptedStrategy(HoldMetrics metrics, List<Object> script) {
      super(new NoTransactions(), metrics, script.size());
      this.scripted = new ArrayDeque<>(script);
    }

    @Override
    public LockingStrategy strategy() {
      return LockingStrategy.CONSTRAINT;
    }

    @Override
    protected HoldOutcome write(HoldAttempt held) {
      Object next = scripted.removeFirst();
      if (next instanceof RuntimeException failure) {
        throw failure;
      }
      return (HoldOutcome) next;
    }
  }
}
