package dev.roomlock.booking.hold;

import dev.roomlock.booking.domain.Booking;
import dev.roomlock.booking.domain.ResourceNotFoundException;
import dev.roomlock.booking.infra.BookingRepository;
import dev.roomlock.booking.infra.RoomRepository;
import dev.roomlock.booking.outbox.BookingOutbox;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Takes no lock up front: it reads the version of the room, checks the stay and then bumps the
 * version only if nobody else has. A lost bump means another hold of this room committed in
 * between, so the attempt rolls back and runs again against the fresh state (rule DB-8).
 */
@Component
class OptimisticHoldStrategy extends TransactionalHoldStrategy {

  private final RoomRepository rooms;
  private final BookingRepository bookings;
  private final BookingOutbox outbox;

  OptimisticHoldStrategy(
      PlatformTransactionManager transactionManager,
      HoldMetrics metrics,
      RoomRepository rooms,
      BookingRepository bookings,
      BookingOutbox outbox,
      LockingProperties properties) {
    super(transactionManager, metrics, properties.maxAttempts());
    this.rooms = rooms;
    this.bookings = bookings;
    this.outbox = outbox;
  }

  @Override
  public LockingStrategy strategy() {
    return LockingStrategy.OPTIMISTIC;
  }

  @Override
  protected HoldOutcome write(HoldAttempt attempt) {
    long version =
        rooms
            .findVersionById(attempt.roomId())
            .orElseThrow(() -> new ResourceNotFoundException("room", attempt.roomId()));
    if (bookings.existsActiveOverlap(
        attempt.roomId(), attempt.stay().checkIn(), attempt.stay().checkOut())) {
      return new HoldOutcome.Busy();
    }
    if (rooms.bumpVersion(attempt.roomId(), version) == 0) {
      return new HoldOutcome.Contended();
    }
    Booking booking = bookings.saveAndFlush(attempt.newBooking());
    outbox.recordHeld(booking);
    return new HoldOutcome.Inserted(booking);
  }
}
