package dev.roomlock.payment.payments;

/**
 * What a repeated charge of one booking id disagrees with the stored payment about (rule I-2). Such
 * a call is refused instead of being answered with the stored decision: the two requests are not
 * the same request, so replaying the answer would hide a bug of the caller.
 */
public enum ChargeMismatch {
  GUEST,
  AMOUNT,
  CURRENCY
}
