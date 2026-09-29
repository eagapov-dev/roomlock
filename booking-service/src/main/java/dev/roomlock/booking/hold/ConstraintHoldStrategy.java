package dev.roomlock.booking.hold;

import dev.roomlock.booking.infra.BookingRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Asks nothing before writing: the insert either fits between the existing bookings or breaks
 * {@code booking_no_overlap}, and the violation is the answer. The shortest path when the room is
 * free, and the only strategy that depends on the constraint being there (D1).
 */
@Component
class ConstraintHoldStrategy extends TransactionalHoldStrategy {

  private static final int ATTEMPTS = 1;

  private final BookingRepository bookings;

  ConstraintHoldStrategy(
      PlatformTransactionManager transactionManager, BookingRepository bookings) {
    super(transactionManager, ATTEMPTS);
    this.bookings = bookings;
  }

  @Override
  public LockingStrategy strategy() {
    return LockingStrategy.CONSTRAINT;
  }

  @Override
  protected HoldOutcome write(HoldAttempt attempt) {
    return new HoldOutcome.Inserted(bookings.saveAndFlush(attempt.newBooking()));
  }
}
