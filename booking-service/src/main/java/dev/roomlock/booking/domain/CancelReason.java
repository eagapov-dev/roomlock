package dev.roomlock.booking.domain;

/** Why a booking ended up CANCELLED, stored as its name (rule DB-1). */
public enum CancelReason {
  HOLD_EXPIRED,
  PAYMENT_DECLINED,
  USER_CANCELLED
}
