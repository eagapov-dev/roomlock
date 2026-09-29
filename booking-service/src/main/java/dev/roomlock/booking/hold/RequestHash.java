package dev.roomlock.booking.hold;

/**
 * SHA-256 of the request a booking was created from, as 64 hex characters. A retry with the same
 * idempotency key is a replay only if its hash matches (rule I-2).
 */
public final class RequestHash {

  private RequestHash() {}

  public static String of(HoldCommand command) {
    throw new UnsupportedOperationException("Not implemented yet");
  }
}
