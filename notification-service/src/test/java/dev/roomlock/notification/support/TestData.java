package dev.roomlock.notification.support;

import dev.roomlock.contract.v1.BookingCancelled;
import dev.roomlock.contract.v1.BookingConfirmed;
import dev.roomlock.contract.v1.BookingHeld;
import dev.roomlock.contract.v1.CancellationReason;
import dev.roomlock.contract.v1.RefundRequested;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Test data factories with defaults and {@code with...} modifiers (rule T-9). */
public final class TestData {

  /** Start of {@link MutableClock} and the moment every default event happened at. */
  public static final Instant NOW = Instant.parse("2026-11-01T09:00:00Z");

  /** Default hold TTL of booking-service, so {@code holdExpiresAt} is 09:10. */
  public static final Duration HOLD_TTL = Duration.ofMinutes(10);

  public static final String CURRENCY = "EUR";
  public static final BigDecimal TOTAL_PRICE = new BigDecimal("450.00");

  private static final int STAY_YEAR = 2026;

  private TestData() {}

  /** A day of November of the test year: {@code november(10)} is the default check-in. */
  public static LocalDate november(int day) {
    return LocalDate.of(STAY_YEAR, 11, day);
  }

  /** Hold of 10-13 November that expires at {@link #NOW} plus {@link #HOLD_TTL}. */
  public static HeldDraft aBookingHeld() {
    return new HeldDraft(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
  }

  /** Confirmation of the same stay, paid for in full. */
  public static ConfirmedDraft aBookingConfirmed() {
    return new ConfirmedDraft(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
  }

  /** Cancellation of the same stay, by the guest unless the reason is replaced. */
  public static CancelledDraft aBookingCancelled() {
    return new CancelledDraft(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), CancellationReason.USER_CANCELLED);
  }

  /** Refund of the same stay; the payment service answers it, notifications do not. */
  public static RefundDraft aRefundRequested() {
    return new RefundDraft(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
  }

  public record HeldDraft(UUID eventId, UUID bookingId, UUID guestId) {

    public HeldDraft withEventId(UUID newEventId) {
      return new HeldDraft(newEventId, bookingId, guestId);
    }

    public HeldDraft withBookingId(UUID newBookingId) {
      return new HeldDraft(eventId, newBookingId, guestId);
    }

    public HeldDraft withGuestId(UUID newGuestId) {
      return new HeldDraft(eventId, bookingId, newGuestId);
    }

    public BookingHeld build() {
      return new BookingHeld(
          eventId,
          bookingId,
          UUID.randomUUID(),
          guestId,
          november(10),
          november(13),
          TOTAL_PRICE,
          CURRENCY,
          NOW.plus(HOLD_TTL),
          NOW);
    }
  }

  public record ConfirmedDraft(UUID eventId, UUID bookingId, UUID guestId) {

    public ConfirmedDraft withEventId(UUID newEventId) {
      return new ConfirmedDraft(newEventId, bookingId, guestId);
    }

    public ConfirmedDraft withBookingId(UUID newBookingId) {
      return new ConfirmedDraft(eventId, newBookingId, guestId);
    }

    public ConfirmedDraft withGuestId(UUID newGuestId) {
      return new ConfirmedDraft(eventId, bookingId, newGuestId);
    }

    public BookingConfirmed build() {
      return new BookingConfirmed(
          eventId,
          bookingId,
          UUID.randomUUID(),
          guestId,
          november(10),
          november(13),
          TOTAL_PRICE,
          CURRENCY,
          NOW);
    }
  }

  public record CancelledDraft(
      UUID eventId, UUID bookingId, UUID guestId, CancellationReason reason) {

    public CancelledDraft withEventId(UUID newEventId) {
      return new CancelledDraft(newEventId, bookingId, guestId, reason);
    }

    public CancelledDraft withBookingId(UUID newBookingId) {
      return new CancelledDraft(eventId, newBookingId, guestId, reason);
    }

    public CancelledDraft withReason(CancellationReason newReason) {
      return new CancelledDraft(eventId, bookingId, guestId, newReason);
    }

    public BookingCancelled build() {
      return new BookingCancelled(eventId, bookingId, UUID.randomUUID(), guestId, reason, NOW);
    }
  }

  public record RefundDraft(UUID eventId, UUID bookingId, UUID guestId) {

    public RefundDraft withEventId(UUID newEventId) {
      return new RefundDraft(newEventId, bookingId, guestId);
    }

    public RefundDraft withBookingId(UUID newBookingId) {
      return new RefundDraft(eventId, newBookingId, guestId);
    }

    public RefundRequested build() {
      return new RefundRequested(eventId, bookingId, guestId, TOTAL_PRICE, CURRENCY, NOW);
    }
  }
}
