package dev.roomlock.contract.v1;

/** Kafka topics of the contract; the suffix is the version of the payloads below. */
public final class Topics {

  /** Everything that happened to a booking, keyed by booking id. Consumed by notifications. */
  public static final String BOOKING_EVENTS = "roomlock.booking-events.v1";

  /** Refunds a cancelled booking asks the payment service for, keyed by booking id. */
  public static final String REFUND_REQUESTS = "roomlock.refund-requests.v1";

  private Topics() {}
}
