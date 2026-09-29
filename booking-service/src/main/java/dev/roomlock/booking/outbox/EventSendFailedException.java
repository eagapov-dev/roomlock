package dev.roomlock.booking.outbox;

/** The broker did not acknowledge an event; the outbox row keeps it for the next sweep. */
public class EventSendFailedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public EventSendFailedException(String message, Throwable cause) {
    super(message, cause);
  }
}
