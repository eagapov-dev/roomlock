package dev.roomlock.booking.hold;

import dev.roomlock.booking.domain.ResourceNotFoundException;
import dev.roomlock.booking.infra.BookingRepository;
import dev.roomlock.booking.infra.RoomRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Locks the room row first: holds of one room run one after another, so the overlap check cannot go
 * stale between the check and the insert. Conflicts cost waiting, never a retry.
 */
@Component
class PessimisticHoldStrategy extends TransactionalHoldStrategy {

  private static final int ATTEMPTS = 1;

  private final RoomRepository rooms;
  private final BookingRepository bookings;

  PessimisticHoldStrategy(
      PlatformTransactionManager transactionManager,
      HoldMetrics metrics,
      RoomRepository rooms,
      BookingRepository bookings) {
    super(transactionManager, metrics, ATTEMPTS);
    this.rooms = rooms;
    this.bookings = bookings;
  }

  @Override
  public LockingStrategy strategy() {
    return LockingStrategy.PESSIMISTIC;
  }

  @Override
  protected HoldOutcome write(HoldAttempt attempt) {
    rooms
        .lockById(attempt.roomId())
        .orElseThrow(() -> new ResourceNotFoundException("room", attempt.roomId()));
    if (bookings.existsActiveOverlap(
        attempt.roomId(), attempt.stay().checkIn(), attempt.stay().checkOut())) {
      return new HoldOutcome.Busy();
    }
    return new HoldOutcome.Inserted(bookings.saveAndFlush(attempt.newBooking()));
  }
}
