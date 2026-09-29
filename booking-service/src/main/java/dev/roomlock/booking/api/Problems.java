package dev.roomlock.booking.api;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

/** The single factory of ProblemDetail responses (rules E-1, E-2). */
@Component
final class Problems {

  private static final String TYPE_PREFIX = "urn:roomlock:problem:";

  /** One rejected field of a request, an element of the {@code errors} array. */
  record Violation(String field, String message) {}

  ProblemDetail validationError(String detail, List<Violation> violations) {
    ProblemDetail problem =
        problem(HttpStatus.BAD_REQUEST, "validation-error", "Validation error", detail);
    problem.setProperty("errors", violations);
    return problem;
  }

  ProblemDetail notFound(String resource, UUID id) {
    ProblemDetail problem =
        problem(
            HttpStatus.NOT_FOUND,
            "not-found",
            "Resource not found",
            resource + " " + id + " does not exist");
    problem.setProperty("resource", resource);
    problem.setProperty("id", id);
    return problem;
  }

  ProblemDetail roomNumberTaken(UUID hotelId, String number) {
    ProblemDetail problem =
        problem(
            HttpStatus.CONFLICT,
            "room-number-taken",
            "Room number taken",
            "Hotel " + hotelId + " already has room " + number);
    problem.setProperty("hotelId", hotelId);
    problem.setProperty("number", number);
    return problem;
  }

  /** Nothing about the failure reaches the client (rule E-2): the cause is logged instead. */
  ProblemDetail internalError() {
    return problem(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "internal-error",
        "Internal error",
        "The request could not be processed. Please try again later.");
  }

  private static ProblemDetail problem(
      HttpStatus status, String type, String title, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setType(URI.create(TYPE_PREFIX + type));
    problem.setTitle(title);
    return problem;
  }
}
