package dev.roomlock.loadtest;

import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.details;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpRequestActionBuilder;

/**
 * The retry of a client that never saw the first answer: the same booking is paid for twice. The
 * charge is idempotent on the booking id, so the second call has to answer 200 with the very same
 * booking, already {@code CONFIRMED} - not a second charge, not a 409 {@code booking-not-payable}
 * because the booking is no longer {@code HELD}.
 *
 * <p>Both answers are checked on the body, not on the status: the id has to be the one of the hold
 * and the status has to be {@code CONFIRMED} already on the first answer, which is what makes the
 * second one a replay of a confirmation rather than a new one. The read afterwards is the same
 * booking a third time, still confirmed.
 *
 * <p>Every user gets a room of its own, so the hold cannot lose a race and 201 is the only correct
 * answer; that caps the phase at the size of the room pool.
 */
public class IdempotentPaySimulation extends Simulation {

  private static final String NAME = "idempotent pay";
  private static final String REPLAY = "pay for the same booking again";

  private final int users = Math.min(LoadTestSettings.USERS, LoadTestSettings.ROOMS);
  private final RoomCatalog catalog = new RoomCatalog(LoadTestSettings.ROOMS);
  private final PayOutcomes payments = new PayOutcomes();

  private final ScenarioBuilder idempotentPay =
      scenario("idempotent pay: the same booking paid twice" + LoadTestSettings.paymentLabel())
          .feed(catalog.freeStays())
          .exec(
              Holds.hold("hold a room of its own")
                  .check(status().is(201))
                  .check(jsonPath("$.id").saveAs(Holds.BOOKING_ID)))
          .exec(confirming("pay for the held booking"))
          .exec(payments.record())
          .exec(confirming(REPLAY))
          .exec(payments.record())
          .exec(Payments.readBack("read the booking paid twice", "CONFIRMED"));

  {
    setUp(
            catalog
                .warmup()
                .injectOpen(atOnceUsers(1))
                .andThen(idempotentPay.injectOpen(atOnceUsers(users))))
        .protocols(LoadTestSettings.httpProtocol())
        .assertions(
            global().failedRequests().count().is(0L),
            // The second pay has to have happened, otherwise the run proves nothing.
            details(REPLAY).allRequests().count().is((long) users));
  }

  @Override
  public void before() {
    System.out.println(LoadTestSettings.paymentBanner(NAME));
  }

  @Override
  public void after() {
    System.out.println(payments.report(NAME));
  }

  /** A pay that has to answer 200 with the confirmed booking of this user - twice over. */
  private static HttpRequestActionBuilder confirming(String name) {
    return Payments.pay(name)
        .check(status().is(200))
        .check(status().saveAs(Payments.STATUS))
        .check(jsonPath("$.id").isEL("#{" + Holds.BOOKING_ID + "}"))
        .check(jsonPath("$.status").is("CONFIRMED"));
  }
}
