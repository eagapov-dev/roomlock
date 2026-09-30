package dev.roomlock.loadtest;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.Session;
import io.gatling.javaapi.http.HttpRequestActionBuilder;

/** {@code POST /api/v1/bookings}, built from the feeder attributes of the virtual user. */
final class Holds {

  /** Status and problem type of the last hold, read back by {@link HoldOutcomes}. */
  static final String STATUS = "holdStatus";

  static final String PROBLEM_TYPE = "holdProblemType";
  static final String BOOKING_ID = "holdBookingId";

  private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

  /**
   * No guestId: the guest is the subject of the access token of the run, and a body that still
   * named one would be answered 400 rather than ignored (HoldBookingRequest).
   */
  private static final String BODY =
      """
      {"roomId":"#{roomId}",\
      "checkIn":"#{checkIn}","checkOut":"#{checkOut}"}""";

  private Holds() {}

  /** A hold with the idempotency key of the virtual user; the caller adds the checks. */
  static HttpRequestActionBuilder hold(String name) {
    return http(name)
        .post("/api/v1/bookings")
        .header(IDEMPOTENCY_KEY, "#{idempotencyKey}")
        .body(StringBody(BODY));
  }

  /**
   * A hold whose outcome is only recorded, never judged: 201 and the expected 409 problems all
   * pass, anything else (5xx, or a 400 of a malformed request) fails the request and with it the
   * run.
   */
  static HttpRequestActionBuilder countedHold(String name) {
    return hold(name)
        .check(status().in(201, 409))
        .check(status().saveAs(STATUS))
        .check(jsonPath("$.type").optional().saveAs(PROBLEM_TYPE))
        .check(jsonPath("$.id").optional().saveAs(BOOKING_ID));
  }

  /** True when the hold created a booking, the only case in which paying for it makes sense. */
  static boolean created(Session session) {
    return session.contains(STATUS) && session.getInt(STATUS) == 201;
  }
}
