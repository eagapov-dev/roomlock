package dev.roomlock.contract.v1;

/** Why a booking was cancelled. Mirrors the {@code booking.cancel_reason} column of bookings. */
public enum CancellationReason {
  HOLD_EXPIRED,
  PAYMENT_DECLINED,
  USER_CANCELLED
}
