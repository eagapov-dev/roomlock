package dev.roomlock.booking.hold;

import static dev.roomlock.booking.support.TestData.aHold;
import static dev.roomlock.booking.support.TestData.aHotel;
import static dev.roomlock.booking.support.TestData.aRoom;
import static dev.roomlock.booking.support.TestData.november;
import static org.assertj.core.api.Assertions.assertThat;

import dev.roomlock.booking.Profiles;
import dev.roomlock.booking.infra.HotelRepository;
import dev.roomlock.booking.infra.RoomRepository;
import dev.roomlock.booking.support.BookingIntegrationTest;
import dev.roomlock.booking.support.BookingTable;
import dev.roomlock.booking.support.Concurrently;
import dev.roomlock.booking.support.DatabaseCleaner;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

/**
 * Profile no-constraint: the database does not guard overlaps, so only the application does (D1).
 * This is where PESSIMISTIC and OPTIMISTIC are actually proven — and where CONSTRAINT, which has no
 * check of its own, overbooks.
 */
@BookingIntegrationTest
@ActiveProfiles({Profiles.TEST, Profiles.NO_CONSTRAINT})
class NoConstraintHoldStrategyTest {

  private static final int RACING_HOLDS = 8;

  @Autowired private HoldStrategies strategies;
  @Autowired private BookingTable bookings;
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
  @EnumSource(
      value = LockingStrategy.class,
      names = {"PESSIMISTIC", "OPTIMISTIC"})
  void shouldReportBusyWhenStayOverlapsWithoutConstraint(LockingStrategy strategy) {
    // given
    HoldStrategy holds = strategies.get(strategy);
    holds.hold(aHold().withRoomId(roomId).withStay(november(10), november(13)).attempt());

    // when
    HoldOutcome outcome =
        holds.hold(aHold().withRoomId(roomId).withStay(november(12), november(14)).attempt());

    // then
    assertThat(outcome).isInstanceOf(HoldOutcome.Busy.class);
    assertThat(bookings.countForRoom(roomId)).isEqualTo(1);
  }

  @ParameterizedTest
  @EnumSource(
      value = LockingStrategy.class,
      names = {"PESSIMISTIC", "OPTIMISTIC"})
  void shouldInsertOneBookingWhenHoldsRaceWithoutConstraint(LockingStrategy strategy) {
    // given
    HoldStrategy holds = strategies.get(strategy);
    List<HoldAttempt> attempts = attemptsForSameStay();

    // when
    List<HoldOutcome> outcomes =
        Concurrently.run(RACING_HOLDS, index -> holds.hold(attempts.get(index)));

    // then
    assertThat(outcomes).filteredOn(HoldOutcome.Inserted.class::isInstance).hasSize(1);
    assertThat(bookings.countForRoom(roomId)).isEqualTo(1);
  }

  @Test
  void shouldOverbookWhenConstraintStrategyRunsWithoutTheConstraint() {
    // given the strategy that leaves the decision to a constraint that this profile skips
    HoldStrategy holds = strategies.get(LockingStrategy.CONSTRAINT);

    // when
    HoldOutcome first =
        holds.hold(aHold().withRoomId(roomId).withStay(november(10), november(13)).attempt());
    HoldOutcome second =
        holds.hold(aHold().withRoomId(roomId).withStay(november(12), november(14)).attempt());

    // then both stays are held: the exclusion constraint is what makes this strategy correct
    assertThat(first).isInstanceOf(HoldOutcome.Inserted.class);
    assertThat(second).isInstanceOf(HoldOutcome.Inserted.class);
    assertThat(bookings.countForRoom(roomId)).isEqualTo(2);
  }

  private List<HoldAttempt> attemptsForSameStay() {
    return IntStream.range(0, RACING_HOLDS)
        .mapToObj(
            index -> aHold().withRoomId(roomId).withStay(november(10), november(13)).attempt())
        .toList();
  }
}
