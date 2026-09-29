package dev.roomlock.notification.infra;

/**
 * A record of the booking events topic that is not an event of the contract: an unknown type, no
 * type header, or a payload that does not parse.
 *
 * <p>Retrying it would fail the same way forever, so the error handler treats it as not retryable
 * and parks it on the dead-letter topic at once (task 3).
 */
class UnreadableEventException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  UnreadableEventException(String message) {
    super(message);
  }

  UnreadableEventException(String message, Throwable cause) {
    super(message, cause);
  }
}
