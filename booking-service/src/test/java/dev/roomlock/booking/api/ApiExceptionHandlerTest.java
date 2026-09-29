package dev.roomlock.booking.api;

import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

/**
 * The one failure that gets no answer. Every other exception becomes a ProblemDetail, which the
 * controller slices assert; a client that hung up has nobody to read one, and answering it used to
 * log an error and then fail again while writing the 500 (rule E-2).
 */
class ApiExceptionHandlerTest {

  private final ApiExceptionHandler handler = new ApiExceptionHandler(new Problems());

  @Test
  void shouldAnswerNothingWhenTheClientWentAway() {
    // given
    AsyncRequestNotUsableException clientGone =
        new AsyncRequestNotUsableException(
            "ServletOutputStream failed to write", new IOException("Broken pipe"));

    // when
    // then
    assertThatCode(() -> handler.onClientGone(clientGone)).doesNotThrowAnyException();
  }
}
