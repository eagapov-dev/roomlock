package dev.roomlock.loadtest;

import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.details;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;

/**
 * The scenario the three locking strategies are compared with: every virtual user holds the same
 * room for the same night at the same moment, each with its own guest and idempotency key. Exactly
 * one of them may win, everybody else has to be told 409 {@code room-busy} (the room was taken) or
 * 409 {@code room-contended} (the race was lost, retrying may help) — both are expected answers
 * here, so they count as successful requests and only their tally tells the strategies apart.
 */
public class ContentionSimulation extends Simulation {

  private static final String NAME = "contention";
  private static final String WINNER = "read the booking of the winner";

  private final RoomCatalog catalog = new RoomCatalog(LoadTestSettings.ROOMS);
  private final HoldOutcomes outcomes = new HoldOutcomes();

  private final ScenarioBuilder contention =
      scenario("contention: all users hold one room for one night" + LoadTestSettings.label())
          .feed(catalog.contendedStays())
          .exec(Holds.countedHold("hold the contended room"))
          .exec(outcomes.record())
          .doIf(session -> session.getInt(Holds.STATUS) == 201)
          .then(
              exec(
                  http(WINNER)
                      .get("/api/v1/bookings/#{" + Holds.BOOKING_ID + "}")
                      .check(status().is(200))));

  {
    setUp(
            catalog
                .warmup()
                .injectOpen(atOnceUsers(1))
                .andThen(contention.injectOpen(atOnceUsers(LoadTestSettings.USERS))))
        .protocols(LoadTestSettings.httpProtocol())
        .assertions(
            // Never on the expected 409s: those pass the checks of the hold.
            global().failedRequests().count().is(0L),
            // One room, one night, one winner - whatever the strategy.
            details(WINNER).allRequests().count().is(1L));
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
