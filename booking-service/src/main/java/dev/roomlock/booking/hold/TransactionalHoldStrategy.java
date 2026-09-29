package dev.roomlock.booking.hold;

import dev.roomlock.booking.infra.SqlState;
import java.util.Objects;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One attempt per transaction, with the outcome read out of a constraint violation.
 *
 * <p>The transaction is opened here instead of by an annotation for two reasons: a strategy that
 * retries needs the lost attempt rolled back before the next one starts, and a violation has to be
 * translated after the rollback, never inside the transaction that broke.
 */
abstract class TransactionalHoldStrategy implements HoldStrategy {

  private final TransactionTemplate transactions;
  private final HoldMetrics metrics;
  private final int maxAttempts;

  TransactionalHoldStrategy(
      PlatformTransactionManager transactionManager, HoldMetrics metrics, int maxAttempts) {
    this.transactions = new TransactionTemplate(transactionManager);
    this.metrics = metrics;
    this.maxAttempts = Math.max(1, maxAttempts);
  }

  @Override
  public final HoldOutcome hold(HoldAttempt attempt) {
    HoldOutcome outcome = new HoldOutcome.Contended();
    for (int number = 1; number <= maxAttempts; number++) {
      outcome = attemptOnce(attempt);
      if (!(outcome instanceof HoldOutcome.Contended)) {
        return outcome;
      }
    }
    return outcome;
  }

  /** Writes the HELD row of the attempt, or reports why the room cannot be held. */
  protected abstract HoldOutcome write(HoldAttempt attempt);

  private HoldOutcome attemptOnce(HoldAttempt attempt) {
    try {
      return Objects.requireNonNull(transactions.execute(status -> write(attempt)));
    } catch (DataIntegrityViolationException violation) {
      return outcomeOf(violation);
    }
  }

  /**
   * {@code booking_no_overlap} means the room was taken while this attempt ran; {@code
   * booking_guest_id_idempotency_key_key} means a retry of the same request won the race.
   */
  private static HoldOutcome outcomeOf(DataIntegrityViolationException violation) {
    if (SqlState.EXCLUSION_VIOLATION.thrownBy(violation)) {
      return new HoldOutcome.Busy();
    }
    if (SqlState.UNIQUE_VIOLATION.thrownBy(violation)) {
      return new HoldOutcome.KeyTaken();
    }
    throw violation;
  }
}
