package dev.roomlock.loadtest;

import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.constantConcurrentUsers;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.scenario;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;

/**
 * The mostly conflict-free path: holds are spread over the whole room pool and over nights that
 * never overlap, so the latency of this run is what a strategy costs when nothing is contended.
 *
 * <p>Two holds of the same room for different nights are still not free of conflicts for every
 * strategy - the optimistic one guards the room row, not the night - so 409 {@code room-contended}
 * stays an expected answer and is counted rather than failed.
 */
public class ThroughputSimulation extends Simulation {

  private static final String NAME = "throughput";

  private final RoomCatalog catalog = new RoomCatalog(LoadTestSettings.ROOMS);
  private final HoldOutcomes outcomes = new HoldOutcomes();

  private final ScenarioBuilder throughput =
      scenario("throughput: holds over many rooms and nights" + LoadTestSettings.label())
          .feed(catalog.freeStays())
          .exec(Holds.countedHold("hold a free room"))
          .exec(outcomes.record());

  {
    setUp(
            catalog
                .warmup()
                .injectOpen(atOnceUsers(1))
                .andThen(
                    throughput.injectClosed(
                        constantConcurrentUsers(LoadTestSettings.USERS)
                            .during(LoadTestSettings.DURATION))))
        .protocols(LoadTestSettings.httpProtocol())
        // Never on the expected 409s: those pass the checks of the hold.
        .assertions(global().failedRequests().count().is(0L));
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
