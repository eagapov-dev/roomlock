package dev.roomlock.notification.infra;

import java.sql.SQLException;

/**
 * The PostgreSQL error code this service reacts to. A recorded delivery claimed by a concurrent
 * consumer is an expected answer rather than a failure, so the code has to be read from the
 * exception chain. Mirrors {@code dev.roomlock.booking.infra.SqlState} of bookings.
 */
public enum SqlState {
  UNIQUE_VIOLATION("23505");

  private final String code;

  SqlState(String code) {
    this.code = code;
  }

  /** Whether the chain of {@code thrown} carries a SQL exception with this state. */
  public boolean thrownBy(Throwable thrown) {
    for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sql && code.equals(sql.getSQLState())) {
        return true;
      }
    }
    return false;
  }
}
