package dev.roomlock.booking.hold;

import dev.roomlock.booking.domain.Booking;
import dev.roomlock.booking.domain.ResourceNotFoundException;
import dev.roomlock.booking.infra.BookingRepository;
import dev.roomlock.booking.infra.RoomRepository;
import dev.roomlock.booking.outbox.BookingOutbox;
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
  private final BookingOutbox outbox;

  PessimisticHoldStrategy(
      PlatformTransactionManager transactionManager,
      HoldMetrics metrics,
      RoomRepository rooms,
      BookingRepository bookings,
      BookingOutbox outbox) {
    super(transactionManager, metrics, ATTEMPTS);
    this.rooms = rooms;
    this.bookings = bookings;
    this.outbox = outbox;
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
    Booking booking = bookings.saveAndFlush(attempt.newBooking());
    outbox.recordHeld(booking);
    return new HoldOutcome.Inserted(booking);
  }
}
