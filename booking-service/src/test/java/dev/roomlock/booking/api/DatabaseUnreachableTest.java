package dev.roomlock.booking.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLTransientConnectionException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * Saturation is not an internal error. A pool that has handed out every connection is the shape a
 * load spike takes here - a hold waits for a connection while the holder of one waits for
 * PostgreSQL to notice a deadlock - and the caller has to be told to come back rather than that
 * something broke (rule E-2).
 */
class DatabaseUnreachableTest {

  private final ApiExceptionHandler handler = new ApiExceptionHandler(new Problems());

  @Test
  void shouldAnswerOverloadedWhenThePoolHasNoConnectionLeft() {
    // given the exception Hikari's timeout surfaces as
    CannotCreateTransactionException poolExhausted =
        new CannotCreateTransactionException(
            "Could not open JPA EntityManager for transaction",
            new SQLTransientConnectionException("Connection is not available, request timed out"));

    // when
    ResponseEntity<ProblemDetail> answer = handler.onDatabaseUnreachable(poolExhausted);

    // then
    assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(answer.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotNull();
    ProblemDetail problem = answer.getBody();
    assertThat(problem).isNotNull();
    assertThat(problem.getType()).hasToString("urn:roomlock:problem:overloaded");
  }

  @Test
  void shouldAnswerOverloadedWhenTheDatabaseIsAway() {
    // given
    DataAccessResourceFailureException databaseDown =
        new DataAccessResourceFailureException("connection refused");

    // when
    ResponseEntity<ProblemDetail> answer = handler.onDatabaseUnreachable(databaseDown);

    // then nothing was decided either way, so the answer is the same
    assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
  }

  @Test
  void shouldSayNothingAboutTheCauseWhenAnsweringOverloaded() {
    // given
    String secret = "jdbc:postgresql://internal-host:5432/booking";
    CannotCreateTransactionException withSecret =
        new CannotCreateTransactionException(secret, new IllegalStateException(secret));

    // when
    ProblemDetail problem = handler.onDatabaseUnreachable(withSecret).getBody();

    // then
    assertThat(problem).isNotNull();
    assertThat(problem.getDetail()).doesNotContain(secret).doesNotContain("postgresql");
  }
}
