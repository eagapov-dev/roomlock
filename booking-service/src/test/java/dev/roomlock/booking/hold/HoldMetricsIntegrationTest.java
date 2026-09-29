package dev.roomlock.booking.hold;

import static dev.roomlock.booking.support.TestData.aHold;
import static dev.roomlock.booking.support.TestData.aHotel;
import static dev.roomlock.booking.support.TestData.aRoom;
import static dev.roomlock.booking.support.TestData.november;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import dev.roomlock.booking.infra.HotelRepository;
import dev.roomlock.booking.infra.RoomRepository;
import dev.roomlock.booking.support.BookingIntegrationTest;
import dev.roomlock.booking.support.DatabaseCleaner;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Every strategy against the real registry of the running application, and the same meters as
 * Prometheus scrapes them (D2).
 *
 * <p>The registry lives as long as the application context, which the tests share, so every
 * assertion compares a count against the count taken before the hold instead of against zero.
 */
@BookingIntegrationTest
class HoldMetricsIntegrationTest {

  private static final String OUTCOMES = "roomlock.hold.outcomes";
  private static final String RETRIES = "roomlock.hold.retries";
  private static final String ATTEMPTS = "roomlock.hold.attempts";
  private static final String STRATEGY = "strategy";
  private static final String OUTCOME = "outcome";

  /** Every outcome a hold can answer with, as the dashboard queries the tag. */
  private static final List<String> OUTCOME_TAGS =
      List.of("inserted", "busy", "contended", "key_taken");

  private static final String PROMETHEUS_PATH = "/actuator/prometheus";

  @Autowired private HoldStrategies strategies;
  @Autowired private MeterRegistry registry;
  @Autowired private TestRestTemplate rest;
  @Autowired private DatabaseCleaner databaseCleaner;
  @Autowired private HotelRepository hotels;
  @Autowired private RoomRepository rooms;

  private UUID roomId;

  @BeforeEach
  void setUp() {
    databaseCleaner.truncateAll();
    UUID hotelId = hotels.save(aHotel().build()).getId();
    roomId = rooms.save(aRoom().withHotelId(hotelId).build()).getId();
  }

  @ParameterizedTest
  @EnumSource(LockingStrategy.class)
  void shouldCountInsertedAndTimeTheAttemptWhenStrategyHoldsFreeRoom(LockingStrategy strategy) {
    // given
    HoldStrategy holds = strategies.get(strategy);
    double inserted = outcomes(strategy, "inserted");
    long attempts = attempts(strategy).count();
    double elapsed = attempts(strategy).totalTime(NANOSECONDS);

    // when
    HoldOutcome outcome = holds.hold(aHold().withRoomId(roomId).attempt());

    // then
    assertThat(outcome).isInstanceOf(HoldOutcome.Inserted.class);
    assertThat(outcomes(strategy, "inserted")).isEqualTo(inserted + 1);
    assertThat(attempts(strategy).count()).isEqualTo(attempts + 1);
    assertThat(attempts(strategy).totalTime(NANOSECONDS)).isGreaterThan(elapsed);
  }

  @ParameterizedTest
  @EnumSource(LockingStrategy.class)
  void shouldCountBusyWhenStayOverlapsAnActiveBooking(LockingStrategy strategy) {
    // given
    HoldStrategy holds = strategies.get(strategy);
    holds.hold(aHold().withRoomId(roomId).withStay(november(10), november(13)).attempt());
    double busy = outcomes(strategy, "busy");

    // when
    HoldOutcome outcome =
        holds.hold(aHold().withRoomId(roomId).withStay(november(12), november(14)).attempt());

    // then
    assertThat(outcome).isInstanceOf(HoldOutcome.Busy.class);
    assertThat(outcomes(strategy, "busy")).isEqualTo(busy + 1);
  }

  @Test
  void shouldRegisterEveryOutcomeAndRetryMeterWhenContextStarts() {
    // given
    // when
    // then
    assertThat(LockingStrategy.values())
        .allSatisfy(
            strategy -> {
              assertThat(OUTCOME_TAGS)
                  .allSatisfy(outcome -> assertThat(outcomes(strategy, outcome)).isNotNegative());
              assertThat(retries(strategy)).isNotNegative();
            });
  }

  @Test
  void shouldExposeHoldMetersOnPrometheusEndpointWhenRoomWasHeld() {
    // given
    strategies.get(LockingStrategy.CONSTRAINT).hold(aHold().withRoomId(roomId).attempt());

    // when
    ResponseEntity<String> response = rest.getForEntity(PROMETHEUS_PATH, String.class);

    // then
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody())
        .contains("roomlock_hold_outcomes_total{outcome=\"inserted\",strategy=\"constraint\"}")
        .contains("roomlock_hold_outcomes_total{outcome=\"busy\",strategy=\"pessimistic\"}")
        .contains("roomlock_hold_retries_total{strategy=\"optimistic\"}")
        .contains("roomlock_hold_attempts_seconds_count{strategy=\"constraint\"}")
        .contains("roomlock_hold_attempts_seconds_bucket");
  }

  private double outcomes(LockingStrategy strategy, String outcome) {
    return registry
        .get(OUTCOMES)
        .tag(STRATEGY, tagOf(strategy))
        .tag(OUTCOME, outcome)
        .counter()
        .count();
  }

  private double retries(LockingStrategy strategy) {
    return registry.get(RETRIES).tag(STRATEGY, tagOf(strategy)).counter().count();
  }

  private Timer attempts(LockingStrategy strategy) {
    return registry.get(ATTEMPTS).tag(STRATEGY, tagOf(strategy)).timer();
  }

  private static String tagOf(LockingStrategy strategy) {
    return strategy.name().toLowerCase(Locale.ROOT);
  }
}
