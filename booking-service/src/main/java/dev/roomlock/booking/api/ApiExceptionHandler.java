package dev.roomlock.booking.api;

import dev.roomlock.booking.api.Problems.Violation;
import dev.roomlock.booking.domain.ResourceNotFoundException;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
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

  /**
   * A parameter of the handler broke its own constraints: an oversized idempotency key, or a body
   * field, since a method with constrained parameters reports the body through this exception too.
   */
  @ExceptionHandler(HandlerMethodValidationException.class)
  ProblemDetail onInvalidParameter(HandlerMethodValidationException exception) {
    List<Violation> violations =
        exception.getParameterValidationResults().stream()
            .flatMap(
                result ->
                    result.getResolvableErrors().stream().map(error -> violation(result, error)))
            .toList();
    return problems.validationError("The request is invalid", violations);
  }

  @ExceptionHandler(MissingRequestHeaderException.class)
  ProblemDetail onMissingHeader(MissingRequestHeaderException exception) {
    Violation violation = new Violation(exception.getHeaderName(), "is required");
    return problems.validationError("A required header is missing", List.of(violation));
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

  /**
   * The client hung up while the answer was being written - a load generator closing its
   * connections, a browser tab going away. There is nobody left to answer, so the only thing to do
   * is to say so at a level nobody pages on: as an unhandled failure it was logged as an error, and
   * the 500 it produced could not be written either.
   */
  @ExceptionHandler(AsyncRequestNotUsableException.class)
  void onClientGone(AsyncRequestNotUsableException exception) {
    log.debug("Client went away before the response was written", exception);
  }

  @ExceptionHandler(Exception.class)
  ProblemDetail onUnexpectedFailure(Exception exception) {
    log.error("Unhandled exception, answering 500", exception);
    return problems.internalError();
  }

  /** A rejected body field knows its own name; anything else is named by its parameter. */
  private static Violation violation(
      ParameterValidationResult result, MessageSourceResolvable error) {
    return error instanceof FieldError field
        ? new Violation(field.getField(), message(error))
        : new Violation(nameOf(result), message(error));
  }

  private static String message(MessageSourceResolvable error) {
    return Optional.ofNullable(error.getDefaultMessage()).orElse("is invalid");
  }

  /** The name a client knows the parameter by: the header name, or the parameter name. */
  private static String nameOf(ParameterValidationResult result) {
    MethodParameter parameter = result.getMethodParameter();
    RequestHeader header = parameter.getParameterAnnotation(RequestHeader.class);
    if (header != null) {
      String named = header.name().isEmpty() ? header.value() : header.name();
      if (!named.isEmpty()) {
        return named;
      }
    }
    return Optional.ofNullable(parameter.getParameterName()).orElse("parameter");
  }

  private static String requiredType(MethodArgumentTypeMismatchException exception) {
    return Optional.ofNullable(exception.getRequiredType())
        .map(Class::getSimpleName)
        .orElse("value");
  }
}
