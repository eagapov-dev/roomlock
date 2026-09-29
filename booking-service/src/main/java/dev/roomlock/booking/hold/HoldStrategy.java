package dev.roomlock.booking.hold;

/**
 * Writes the HELD row without letting two guests hold the same room for the same night. Each
 * implementation owns its transactions: an attempt commits or rolls back inside {@link #hold}.
 */
public interface HoldStrategy {

  /** The strategy this implementation is selected by. */
  LockingStrategy strategy();

  HoldOutcome hold(HoldAttempt attempt);
}
