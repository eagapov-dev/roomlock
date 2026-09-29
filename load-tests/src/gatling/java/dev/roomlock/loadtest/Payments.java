package dev.roomlock.loadtest;

import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.Session;
import io.gatling.javaapi.http.HttpRequestActionBuilder;

/**
 * {@code POST /api/v1/bookings/{id}/pay} of the booking the virtual user is holding, and the read
 * of that booking afterwards - the charge is what the API answers, the booking is what it left
 * behind.
 *
 * <p>The request has no body: the amount is the total of the stay, and the provider decides on it.
 */
final class Payments {

  /** Status and problem type of the last pay, read back by {@link PayOutcomes}. */
  static final String STATUS = "payStatus";

  static final String PROBLEM_TYPE = "payProblemType";

  private Payments() {}

  /** A pay of the held booking; the caller adds the checks. */
  static HttpRequestActionBuilder pay(String name) {
    return http(name).post("/api/v1/bookings/#{" + Holds.BOOKING_ID + "}/pay");
  }

  /**
   * A pay that is expected to be approved: 200 with the confirmed booking, or the documented 503
   * {@code payment-unavailable} of a provider that cannot be reached - which leaves the booking in
   * {@code PAYING} for the reconciliation sweep and is therefore not a failed request either.
   */
  static HttpRequestActionBuilder approvedPay(String name) {
    return counted(pay(name), 200);
  }

  /**
   * A pay that is expected to be declined: 409 {@code payment-declined} with the cancelled booking,
   * or the same 503. A 200 here means the provider approved after all and fails the request,
   * because then the run proves nothing about the decline path.
   */
  static HttpRequestActionBuilder declinedPay(String name) {
    return counted(pay(name), 409);
  }

  /** The booking as the API shows it, read back to prove the state the pay left behind. */
  static HttpRequestActionBuilder readBack(String name, String bookingStatus) {
    return http(name)
        .get("/api/v1/bookings/#{" + Holds.BOOKING_ID + "}")
        .check(status().is(200))
        .check(jsonPath("$.id").isEL("#{" + Holds.BOOKING_ID + "}"))
        .check(jsonPath("$.status").is(bookingStatus));
  }

  /** True when the pay answered with the status the scenario is built around. */
  static boolean answered(Session session, int expected) {
    return session.contains(STATUS) && session.getInt(STATUS) == expected;
  }

  private static HttpRequestActionBuilder counted(HttpRequestActionBuilder pay, int expected) {
    return pay.check(status().in(expected, 503))
        .check(status().saveAs(STATUS))
        // Only a problem+json body has a type; a 200 answers with the booking.
        .check(jsonPath("$.type").optional().saveAs(PROBLEM_TYPE));
  }
}
