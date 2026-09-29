package dev.roomlock.loadtest;

import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.details;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.scenario;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;

/**
 * The other end of the funnel: the charge is declined, and the room has to come back. The stay is
 * priced above the limit of the provider in {@code THRESHOLD} mode ({@code
 * roomlock.declinedPricePerNight}, 2500.00 by default against a limit of 1000.00), so the decline
 * is driven by the request itself and nothing has to be reconfigured; a run against {@code
 * DECLINE_ALL} exercises the same path with any price.
 *
 * <p>A simulation of its own rather than a switch on {@link ConfirmFlowSimulation}, because the two
 * assert opposite things about the same funnel - {@code CONFIRMED} there, {@code CANCELLED} here -
 * and a run should fail when a booking ends in the wrong one of them, not quietly count it.
 *
 * <p>The 409 {@code payment-declined} is the expected answer here and passes the checks; a 200 does
 * not, because a provider that approved after all leaves this run proving nothing.
 */
public class DeclineFlowSimulation extends Simulation {

  private static final String NAME = "decline flow";
  private static final String CANCELLED = "read the cancelled booking";

  private final RoomCatalog catalog =
      new RoomCatalog(LoadTestSettings.ROOMS, LoadTestSettings.DECLINED_PRICE_PER_NIGHT);
  private final HoldOutcomes holds = new HoldOutcomes();
  private final PayOutcomes payments = new PayOutcomes();

  private final ScenarioBuilder declineFlow =
      scenario(
              "decline flow: hold a room and get the charge declined"
                  + LoadTestSettings.paymentLabel())
          .feed(catalog.freeStays())
          .exec(Holds.countedHold("hold an expensive room"))
          .exec(holds.record())
          .doIf(Holds::created)
          .then(
              exec(Payments.declinedPay("pay too much for the held booking"))
                  .exec(payments.record())
                  .doIf(session -> Payments.answered(session, 409))
                  .then(exec(Payments.readBack(CANCELLED, "CANCELLED"))));

  {
    setUp(
            catalog
                .warmup()
                .injectOpen(atOnceUsers(1))
                .andThen(declineFlow.injectOpen(atOnceUsers(LoadTestSettings.USERS))))
        .protocols(LoadTestSettings.httpProtocol())
        .assertions(
            // Never on the expected 409s: the decline is what this run is here for.
            global().failedRequests().count().is(0L),
            // At least one booking has to have been cancelled by a decline.
            details(CANCELLED).allRequests().count().gt(0L));
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
