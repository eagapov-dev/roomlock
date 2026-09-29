package dev.roomlock.notification.notify;

import dev.roomlock.contract.v1.BookingCancelled;
import dev.roomlock.contract.v1.BookingConfirmed;
import dev.roomlock.contract.v1.BookingEvent;
import dev.roomlock.contract.v1.BookingHeld;
import dev.roomlock.contract.v1.CancellationReason;
import dev.roomlock.contract.v1.RefundRequested;
import dev.roomlock.notification.domain.GuestNotification;
import dev.roomlock.notification.domain.NotificationKind;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Turns a booking event into the text the guest reads. The switch is exhaustive over the sealed
 * {@link BookingEvent}, so a new event of the contract cannot be forgotten here: it stops
 * compiling.
 */
@Component
public class NotificationMessages {

  /** Deadlines are shown in UTC, the zone every instant of the contract is in (rule SP-12). */
  private static final DateTimeFormatter DEADLINE =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

  private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;

  /**
   * The message for the event, or empty for an event no guest is told about: {@link
   * RefundRequested} is addressed to the payment service, and the guest already heard about the
   * cancellation that caused it.
   */
  public Optional<GuestNotification> render(BookingEvent event) {
    return switch (event) {
      case BookingHeld held -> Optional.of(held(held));
      case BookingConfirmed confirmed -> Optional.of(confirmed(confirmed));
      case BookingCancelled cancelled -> Optional.of(cancelled(cancelled));
      case RefundRequested ignored -> Optional.empty();
    };
  }

  private static GuestNotification held(BookingHeld held) {
    String body =
        "Booking %s: your room is held until %s. It is released if the stay is not paid for by then."
            .formatted(held.bookingId(), DEADLINE.format(held.holdExpiresAt()));
    return new GuestNotification(
        held.bookingId(), held.guestId(), NotificationKind.HOLD, "Your room is held", body);
  }

  private static GuestNotification confirmed(BookingConfirmed confirmed) {
    String body =
        "Booking %s: your stay is confirmed. Check in on %s, check out on %s. Paid: %s %s."
            .formatted(
                confirmed.bookingId(),
                DAY.format(confirmed.checkIn()),
                DAY.format(confirmed.checkOut()),
                confirmed.totalPrice().toPlainString(),
                confirmed.currency());
    return new GuestNotification(
        confirmed.bookingId(),
        confirmed.guestId(),
        NotificationKind.CONFIRMATION,
        "Your booking is confirmed",
        body);
  }

  private static GuestNotification cancelled(BookingCancelled cancelled) {
    String body =
        "Booking %s was cancelled: %s."
            .formatted(cancelled.bookingId(), because(cancelled.reason()));
    return new GuestNotification(
        cancelled.bookingId(),
        cancelled.guestId(),
        NotificationKind.CANCELLATION,
        "Your booking was cancelled",
        body);
  }

  private static String because(CancellationReason reason) {
    return switch (reason) {
      case HOLD_EXPIRED -> "the hold on the room expired before the stay was paid for";
      case PAYMENT_DECLINED -> "the payment was declined";
      case USER_CANCELLED -> "you cancelled it";
    };
  }
}
