package dev.roomlock.booking.lifecycle;

import dev.roomlock.booking.domain.Booking;
import dev.roomlock.booking.domain.CancelReason;
import dev.roomlock.booking.domain.ResourceNotFoundException;
import dev.roomlock.booking.infra.BookingRepository;
import dev.roomlock.booking.outbox.BookingOutbox;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
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
  private final BookingOutbox outbox;
  private final HoldExpiryProperties expiry;
  private final Clock clock;

  public LifecycleService(
      BookingRepository bookings, BookingOutbox outbox, HoldExpiryProperties expiry, Clock clock) {
    this.bookings = bookings;
    this.outbox = outbox;
    this.expiry = expiry;
    this.clock = clock;
  }

  /**
   * Cancels one batch of expired holds and returns how many of them were cancelled. One transaction
   * per batch, not per backlog: the statement cancels at most {@code batchSize} rows and their
   * events are written beside them (rule K-1), so the transaction stays as short as the batch.
   */
  @Transactional
  public int expireHolds() {
    Instant now = clock.instant();
    List<UUID> expired = bookings.expireHolds(now, expiry.batchSize());
    bookings
        .findAllById(expired)
        .forEach(booking -> outbox.recordCancelled(booking, CancelReason.HOLD_EXPIRED));
    return expired.size();
  }

  /**
   * Cancels the booking on behalf of its guest. The conditional UPDATE decides the outcome: it
   * either wins, or the row is read back to say why it did not (rule J-2).
   */
  @Transactional
  public CancelResult cancel(UUID bookingId) {
    if (bookings.cancelHeld(bookingId, clock.instant()) > 0) {
      Booking cancelled = get(bookingId);
      outbox.recordCancelled(cancelled, CancelReason.USER_CANCELLED);
      return new CancelResult.Cancelled(cancelled);
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
