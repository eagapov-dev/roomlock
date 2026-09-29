package dev.roomlock.booking.api;

import dev.roomlock.booking.api.Problems.Violation;
import dev.roomlock.booking.domain.ResourceNotFoundException;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** The only place that turns an exception into a response (rules E-1, E-2, E-4). */
@RestControllerAdvice
class ApiExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  private final Problems problems;

  ApiExceptionHandler(Problems problems) {
    this.problems = problems;
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ProblemDetail onInvalidBody(MethodArgumentNotValidException exception) {
    List<Violation> violations =
        exception.getBindingResult().getFieldErrors().stream()
            .map(error -> new Violation(error.getField(), message(error)))
            .toList();
    return problems.validationError("The request body is invalid", violations);
  }

  /** Malformed JSON never reaches a field, so the response carries no {@code errors} entry. */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  ProblemDetail onUnreadableBody(HttpMessageNotReadableException exception) {
    log.debug("Unreadable request body", exception);
    return problems.validationError("The request body is not readable JSON", List.of());
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ProblemDetail onTypeMismatch(MethodArgumentTypeMismatchException exception) {
    Violation violation =
        new Violation(exception.getName(), "must be a valid " + requiredType(exception));
    return problems.validationError("A request parameter has a wrong type", List.of(violation));
  }

  @ExceptionHandler(ResourceNotFoundException.class)
  ProblemDetail onNotFound(ResourceNotFoundException exception) {
    return problems.notFound(exception.resource(), exception.id());
  }

  @ExceptionHandler(Exception.class)
  ProblemDetail onUnexpectedFailure(Exception exception) {
    log.error("Unhandled exception, answering 500", exception);
    return problems.internalError();
  }

  private static String message(FieldError error) {
    return Optional.ofNullable(error.getDefaultMessage()).orElse("is invalid");
  }

  private static String requiredType(MethodArgumentTypeMismatchException exception) {
    return Optional.ofNullable(exception.getRequiredType())
        .map(Class::getSimpleName)
        .orElse("value");
  }
}
