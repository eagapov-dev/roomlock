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
 * Booking lifecycle use cases that need no call to the payment provider: hold expiry and guest
 * cancellation. Every transition is a conditional native UPDATE of the repository, never a write
 * through the entity (rule DB-5), and each stamps {@code updated_at} from the injected clock (rule
 * SP-12). A cancelled stay that had been paid for asks for its money back with an event rather than
 * a call, so payment-service can be down while a guest cancels (rule K-1).
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
   * Cancels the booking of this guest. The conditional UPDATE decides the outcome: it either wins,
   * or the row is read back to say why it did not (rule J-2).
   *
   * <p>Two UPDATEs rather than one that matches both statuses, because the two endings owe the
   * guest different things and the old status is gone once the row is written: a HELD booking only
   * loses its room, a CONFIRMED one was paid for and gets a {@code RefundRequested} next to its
   * cancellation, in the same transaction (rule K-1). A PAYING booking is refused either way - a
   * charge is in flight and only the payment flow may decide how it ends.
   */
  @Transactional
  public CancelResult cancel(UUID bookingId, UUID guestId) {
    // Ownership first, and as a read of the row rather than a check of one already read: a booking
    // of another guest has to come out of here as 404, not 403 - a 403 confirms the id exists.
    // guest_id never changes, so the UPDATEs below may keep matching on the id alone.
    ownedBy(bookingId, guestId);
    Instant now = clock.instant();
    if (bookings.cancelHeld(bookingId, now) > 0) {
      Booking cancelled = get(bookingId);
      outbox.recordCancelled(cancelled, CancelReason.USER_CANCELLED);
      return new CancelResult.Cancelled(cancelled);
    }
    if (bookings.cancelConfirmed(bookingId, now) > 0) {
      Booking cancelled = get(bookingId);
      outbox.recordCancelled(cancelled, CancelReason.USER_CANCELLED);
      outbox.recordRefundRequested(cancelled);
      return new CancelResult.Cancelled(cancelled);
    }
    Booking booking = get(bookingId);
    return switch (booking.getStatus()) {
      case CANCELLED -> new CancelResult.AlreadyCancelled(booking);
      // HELD and CONFIRMED cannot reach this branch: their UPDATE waits for a concurrent writer and
      // then matches.
      case HELD, PAYING, CONFIRMED ->
          new CancelResult.NotCancellable(bookingId, booking.getStatus());
    };
  }

  private Booking get(UUID bookingId) {
    return bookings
        .findById(bookingId)
        .orElseThrow(() -> new ResourceNotFoundException(BOOKING, bookingId));
  }

  private void ownedBy(UUID bookingId, UUID guestId) {
    if (bookings.findByIdAndGuestId(bookingId, guestId).isEmpty()) {
      throw new ResourceNotFoundException(BOOKING, bookingId);
    }
  }
}
