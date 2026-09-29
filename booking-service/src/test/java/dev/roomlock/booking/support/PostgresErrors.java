package dev.roomlock.booking.support;

import java.util.Optional;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/** Reads PostgreSQL error details from an exception chain, for assertions on constraints. */
public final class PostgresErrors {

  public static final String UNIQUE_VIOLATION = "23505";
  public static final String CHECK_VIOLATION = "23514";
  public static final String EXCLUSION_VIOLATION = "23P01";

  private PostgresErrors() {}

  /**
   * SQLState of the first {@link PSQLException} in the cause chain; empty if nothing was thrown.
   */
  public static Optional<String> sqlState(Throwable thrown) {
    return psqlException(thrown).map(PSQLException::getSQLState);
  }

  /** Name of the violated constraint reported by the server; empty if unknown. */
  public static Optional<String> constraintName(Throwable thrown) {
    return psqlException(thrown)
        .map(PSQLException::getServerErrorMessage)
        .map(ServerErrorMessage::getConstraint);
  }

  private static Optional<PSQLException> psqlException(Throwable thrown) {
    for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
      if (cause instanceof PSQLException psql) {
        return Optional.of(psql);
      }
    }
    return Optional.empty();
  }
}
