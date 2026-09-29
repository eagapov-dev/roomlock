package dev.roomlock.booking.hold;

import dev.roomlock.booking.domain.Booking;
import dev.roomlock.booking.infra.BookingRepository;
import dev.roomlock.booking.outbox.BookingOutbox;
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
  private final BookingOutbox outbox;

  ConstraintHoldStrategy(
      PlatformTransactionManager transactionManager,
      HoldMetrics metrics,
      BookingRepository bookings,
      BookingOutbox outbox) {
    super(transactionManager, metrics, ATTEMPTS);
    this.bookings = bookings;
    this.outbox = outbox;
  }

  @Override
  public LockingStrategy strategy() {
    return LockingStrategy.CONSTRAINT;
  }

  @Override
  protected HoldOutcome write(HoldAttempt attempt) {
    Booking booking = bookings.saveAndFlush(attempt.newBooking());
    outbox.recordHeld(booking);
    return new HoldOutcome.Inserted(booking);
  }
}
