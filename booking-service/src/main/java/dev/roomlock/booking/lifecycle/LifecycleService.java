package dev.roomlock.booking.lifecycle;

import dev.roomlock.booking.domain.Booking;
import dev.roomlock.booking.domain.ResourceNotFoundException;
import dev.roomlock.booking.infra.BookingRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Booking lifecycle use cases that need no payment provider: hold expiry and guest cancellation.
 * Both transitions are conditional native UPDATEs of the repository, never a write through the
 * entity (rule DB-5), and both stamp {@code updated_at} from the injected clock (rule SP-12).
 */
@Service
public class LifecycleService {

  private static final String BOOKING = "booking";

  private final BookingRepository bookings;
  private final HoldExpiryProperties expiry;
  private final Clock clock;

  public LifecycleService(BookingRepository bookings, HoldExpiryProperties expiry, Clock clock) {
    this.bookings = bookings;
    this.expiry = expiry;
    this.clock = clock;
  }

  /**
   * Cancels one batch of expired holds and returns how many of them were cancelled. Not
   * transactional here: the sweep is a single statement, which the repository runs in its own
   * transaction, so a long backlog never becomes one long transaction.
   */
  public int expireHolds() {
    return bookings.expireHolds(clock.instant(), expiry.batchSize());
  }

  /**
   * Cancels the booking on behalf of its guest. The conditional UPDATE decides the outcome: it
   * either wins, or the row is read back to say why it did not (rule J-2).
   */
  @Transactional
  public CancelResult cancel(UUID bookingId) {
    if (bookings.cancelHeld(bookingId, clock.instant()) > 0) {
      return new CancelResult.Cancelled(get(bookingId));
    }
    Booking booking = get(bookingId);
    return switch (booking.getStatus()) {
      case CANCELLED -> new CancelResult.AlreadyCancelled(booking);
      // HELD cannot reach this branch: the UPDATE waits for a concurrent writer and then matches.
      case HELD, PAYING, CONFIRMED ->
          new CancelResult.NotCancellable(bookingId, booking.getStatus());
    };
  }

  private Booking get(UUID bookingId) {
    return bookings
        .findById(bookingId)
        .orElseThrow(() -> new ResourceNotFoundException(BOOKING, bookingId));
  }
}
