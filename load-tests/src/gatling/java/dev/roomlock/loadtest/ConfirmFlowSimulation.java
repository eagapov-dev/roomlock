package dev.roomlock.loadtest;

import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.constantConcurrentUsers;
import static io.gatling.javaapi.core.CoreDsl.details;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.scenario;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;

/**
 * The whole funnel a guest goes through: hold a room, then pay for it. This is the load the three
 * locking strategies have to be compared under, because it writes the confirmation too - the hold
 * takes the room, the charge moves the same booking on to {@code CONFIRMED} - and a strategy that
 * looks cheap while only holding may not stay cheap while a second writer follows every hold.
 *
 * <p>Stays are spread over the room pool, so the hold is the mostly conflict-free path of {@link
 * ThroughputSimulation} and a 409 {@code room-contended} stays an expected answer; only a hold that
 * created a booking goes on to pay. The rooms are priced below the threshold of the provider, so
 * the charge is approved - that a 200 really confirmed the booking is not taken on trust but read
 * back.
 */
public class ConfirmFlowSimulation extends Simulation {

  private static final String NAME = "confirm flow";
  private static final String CONFIRMED = "read the confirmed booking";

  private final RoomCatalog catalog = new RoomCatalog(LoadTestSettings.ROOMS);
  private final HoldOutcomes holds = new HoldOutcomes();
  private final PayOutcomes payments = new PayOutcomes();

  private final ScenarioBuilder confirmFlow =
      scenario("confirm flow: hold a room and pay for it" + LoadTestSettings.paymentLabel())
          .feed(catalog.freeStays())
          .exec(Holds.countedHold("hold a free room"))
          .exec(holds.record())
          .doIf(Holds::created)
          .then(
              exec(Payments.approvedPay("pay for the held booking"))
                  .exec(payments.record())
                  .doIf(session -> Payments.answered(session, 200))
                  .then(exec(Payments.readBack(CONFIRMED, "CONFIRMED"))));

  {
    setUp(
            catalog
                .warmup()
                .injectOpen(atOnceUsers(1))
                .andThen(
                    confirmFlow.injectClosed(
                        constantConcurrentUsers(LoadTestSettings.USERS)
                            .during(LoadTestSettings.DURATION))))
        .protocols(LoadTestSettings.httpProtocol())
        .assertions(
            // Never on the expected 409s of a hold, nor on the documented 503 of the provider.
            global().failedRequests().count().is(0L),
            // A run in which nothing was ever confirmed proves nothing about the funnel.
            details(CONFIRMED).allRequests().count().gt(0L));
  }

  @Override
  public void before() {
    System.out.println(LoadTestSettings.paymentBanner(NAME));
  }

  @Override
  public void after() {
    System.out.println(holds.report(NAME));
    System.out.println(payments.report(NAME));
  }
}
