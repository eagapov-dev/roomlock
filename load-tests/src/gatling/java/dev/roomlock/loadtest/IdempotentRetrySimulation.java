package dev.roomlock.loadtest;

import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.details;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;

/**
 * The retry a client is invited to make: the very same hold is sent twice with the same {@code
 * Idempotency-Key} and the second answer has to be 200 with the booking of the first one, not a
 * second booking and not a 409.
 *
 * <p>Every user gets a room of its own, so the first hold cannot lose a race and 201 is the only
 * correct answer; that caps the phase at the size of the room pool.
 */
public class IdempotentRetrySimulation extends Simulation {

  private static final String NAME = "idempotent retry";
  private static final String REPLAY = "replay the hold with the same key";

  private final int users = Math.min(LoadTestSettings.USERS, LoadTestSettings.ROOMS);
  private final RoomCatalog catalog = new RoomCatalog(LoadTestSettings.ROOMS);
  private final HoldOutcomes outcomes = new HoldOutcomes();

  private final ScenarioBuilder idempotentRetry =
      scenario("idempotent retry: the same hold twice" + LoadTestSettings.label())
          .feed(catalog.freeStays())
          .exec(
              Holds.hold("hold a room of its own")
                  .check(status().is(201))
                  .check(jsonPath("$.id").saveAs(Holds.BOOKING_ID)))
          .exec(
              Holds.hold(REPLAY)
                  .check(status().is(200))
                  .check(jsonPath("$.id").isEL("#{" + Holds.BOOKING_ID + "}")))
          .exec(
              session -> {
                outcomes.count("200 replayed");
                return session;
              });

  {
    setUp(
            catalog
                .warmup()
                .injectOpen(atOnceUsers(1))
                .andThen(idempotentRetry.injectOpen(atOnceUsers(users))))
        .protocols(LoadTestSettings.httpProtocol())
        .assertions(
            global().failedRequests().count().is(0L),
            // The replay has to have happened, otherwise the run proves nothing.
            details(REPLAY).allRequests().count().is((long) users));
  }

  @Override
  public void before() {
    System.out.println(LoadTestSettings.banner(NAME));
  }

  @Override
  public void after() {
    System.out.println(outcomes.report(NAME));
  }
}
