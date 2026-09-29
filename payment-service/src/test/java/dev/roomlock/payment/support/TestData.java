package dev.roomlock.payment.support;

import dev.roomlock.contract.v1.RefundRequested;
import dev.roomlock.payment.api.v1.ChargeRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Test data factories with defaults and {@code with...} modifiers (rule T-9). */
public final class TestData {

  /** Start of {@link MutableClock} and the moment every default event happened at. */
  public static final Instant NOW = Instant.parse("2026-11-01T09:00:00Z");

  /** The currency of the contract comments. */
  public static final String CURRENCY = "PLN";

  /** 450.00 PLN in grosze: below {@code roomlock.payment.decision.decline-above-minor}. */
  public static final long AMOUNT_MINOR = 45_000L;

  /** 1500.00 PLN in grosze: above the limit, so the decision rule declines it. */
  public static final long AMOUNT_MINOR_ABOVE_LIMIT = 150_000L;

  private TestData() {}

  /** An approved-by-default charge of one booking. */
  public static ChargeDraft aCharge() {
    return new ChargeDraft(UUID.randomUUID(), UUID.randomUUID(), AMOUNT_MINOR, CURRENCY);
  }

  /** A refund request of the contract, as booking-service publishes it. */
  public static RefundDraft aRefundRequested() {
    return new RefundDraft(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
  }

  public record ChargeDraft(UUID bookingId, UUID guestId, long amountMinor, String currency) {

    public ChargeDraft withBookingId(UUID newBookingId) {
      return new ChargeDraft(newBookingId, guestId, amountMinor, currency);
    }

    public ChargeDraft withGuestId(UUID newGuestId) {
      return new ChargeDraft(bookingId, newGuestId, amountMinor, currency);
    }

    public ChargeDraft withAmountMinor(long newAmountMinor) {
      return new ChargeDraft(bookingId, guestId, newAmountMinor, currency);
    }

    public ChargeDraft withCurrency(String newCurrency) {
      return new ChargeDraft(bookingId, guestId, amountMinor, newCurrency);
    }

    /** The same booking and guest, for an amount the decision rule declines. */
    public ChargeDraft aboveTheLimit() {
      return withAmountMinor(AMOUNT_MINOR_ABOVE_LIMIT);
    }

    public ChargeRequest build() {
      return ChargeRequest.newBuilder()
          .setBookingId(bookingId.toString())
          .setGuestId(guestId.toString())
          .setAmountMinor(amountMinor)
          .setCurrency(currency)
          .build();
    }
  }

  public record RefundDraft(UUID eventId, UUID bookingId, UUID guestId) {

    public RefundDraft withEventId(UUID newEventId) {
      return new RefundDraft(newEventId, bookingId, guestId);
    }

    public RefundDraft withBookingId(UUID newBookingId) {
      return new RefundDraft(eventId, newBookingId, guestId);
    }

    /** The charge whose money this request asks back, with the same booking and guest. */
    public ChargeDraft itsCharge() {
      return new ChargeDraft(bookingId, guestId, AMOUNT_MINOR, CURRENCY);
    }

    public RefundRequested build() {
      return new RefundRequested(
          eventId,
          bookingId,
          guestId,
          // The event carries the price in major units; the charge is in minor ones.
          BigDecimal.valueOf(AMOUNT_MINOR, 2),
          CURRENCY,
          NOW);
    }
  }
}
