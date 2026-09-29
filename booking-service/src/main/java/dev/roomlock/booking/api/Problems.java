package dev.roomlock.booking.api;

import dev.roomlock.booking.domain.BookingStatus;
import dev.roomlock.booking.domain.DateRange;
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

  ProblemDetail roomBusy(UUID roomId, DateRange stay) {
    ProblemDetail problem =
        problem(
            HttpStatus.CONFLICT,
            "room-busy",
            "Room busy",
            "Room "
                + roomId
                + " is booked for a night of "
                + stay.checkIn()
                + " - "
                + stay.checkOut());
    problem.setProperty("roomId", roomId);
    problem.setProperty("checkIn", stay.checkIn());
    problem.setProperty("checkOut", stay.checkOut());
    return problem;
  }

  /** Every attempt of the hold lost the room to a concurrent request; retrying may succeed. */
  ProblemDetail roomContended(UUID roomId) {
    ProblemDetail problem =
        problem(
            HttpStatus.CONFLICT,
            "room-contended",
            "Room contended",
            "Room " + roomId + " is being booked by someone else. Please retry.");
    problem.setProperty("roomId", roomId);
    return problem;
  }

  /** The booking is past the point its guest may drop it: CONFIRMED, or in PAYING. */
  ProblemDetail bookingNotCancellable(UUID bookingId, BookingStatus status) {
    ProblemDetail problem =
        problem(
            HttpStatus.CONFLICT,
            "booking-not-cancellable",
            "Booking not cancellable",
            "Booking " + bookingId + " is " + status + " and can no longer be cancelled");
    problem.setProperty("bookingId", bookingId);
    problem.setProperty("status", status);
    return problem;
  }

  ProblemDetail idempotencyKeyReused(String idempotencyKey) {
    ProblemDetail problem =
        problem(
            HttpStatus.CONFLICT,
            "idempotency-key-reused",
            "Idempotency key reused",
            "This idempotency key belongs to a different request");
    problem.setProperty("idempotencyKey", idempotencyKey);
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
