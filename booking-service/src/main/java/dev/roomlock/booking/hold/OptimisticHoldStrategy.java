package dev.roomlock.booking.hold;

import dev.roomlock.booking.domain.ResourceNotFoundException;
import dev.roomlock.booking.infra.BookingRepository;
import dev.roomlock.booking.infra.RoomRepository;
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

  OptimisticHoldStrategy(
      PlatformTransactionManager transactionManager,
      RoomRepository rooms,
      BookingRepository bookings,
      LockingProperties properties) {
    super(transactionManager, properties.maxAttempts());
    this.rooms = rooms;
    this.bookings = bookings;
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
    return new HoldOutcome.Inserted(bookings.saveAndFlush(attempt.newBooking()));
  }
}
