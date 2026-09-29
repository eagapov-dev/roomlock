package dev.roomlock.payment.domain;

/**
 * Outcome of one payment, stored as its name (rule DB-1). The names are the ones the {@code
 * payment.status} CHECK allows.
 */
public enum PaymentStatus {
  /** The charge went through; the money can still go back. */
  APPROVED,

  /** The decision rule refused the charge; nothing was taken, so nothing can be refunded. */
  DECLINED,

  /** An approved charge was paid back. */
  REFUNDED
}
