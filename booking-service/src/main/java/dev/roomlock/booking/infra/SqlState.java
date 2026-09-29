package dev.roomlock.booking.infra;

import java.sql.SQLException;

/**
 * The PostgreSQL error codes the booking flow reacts to. A violated constraint is an expected
 * answer of a strategy, not a failure, so the code has to be read from the exception chain.
 */
public enum SqlState {
  UNIQUE_VIOLATION("23505"),
  EXCLUSION_VIOLATION("23P01");

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
