package dev.roomlock.booking.hold;

import static dev.roomlock.booking.support.TestData.aHold;
import static dev.roomlock.booking.support.TestData.aHotel;
import static dev.roomlock.booking.support.TestData.aRoom;
import static dev.roomlock.booking.support.TestData.november;
import static org.assertj.core.api.Assertions.assertThat;

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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Every strategy under contention, with the exclusion constraint in place (task 2.2): the room is
 * held once, and a strategy never rejects a stay that is actually free.
 */
@BookingIntegrationTest
class HoldStrategyConcurrencyTest {

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
  @EnumSource(LockingStrategy.class)
  void shouldInsertOneBookingWhenHoldsRaceForTheSameStay(LockingStrategy strategy) {
    // given
    HoldStrategy holds = strategies.get(strategy);
    List<HoldAttempt> attempts = attemptsForSameStay();

    // when
    List<HoldOutcome> outcomes =
        Concurrently.run(RACING_HOLDS, index -> holds.hold(attempts.get(index)));

    // then
    assertThat(outcomes).filteredOn(HoldOutcome.Inserted.class::isInstance).hasSize(1);
    assertThat(outcomes)
        .allMatch(
            outcome ->
                outcome instanceof HoldOutcome.Inserted
                    || outcome instanceof HoldOutcome.Busy
                    || outcome instanceof HoldOutcome.Contended);
    assertThat(bookings.countForRoom(roomId)).isEqualTo(1);
  }

  @ParameterizedTest
  @EnumSource(LockingStrategy.class)
  void shouldInsertEveryBookingWhenRacingHoldsAskForFreeStays(LockingStrategy strategy) {
    // given
    HoldStrategy holds = strategies.get(strategy);
    List<HoldAttempt> attempts = attemptsForStaysOneAfterAnother();

    // when
    List<HoldOutcome> outcomes =
        Concurrently.run(RACING_HOLDS, index -> holds.hold(attempts.get(index)));

    // then
    assertThat(outcomes).allMatch(HoldOutcome.Inserted.class::isInstance);
    assertThat(bookings.countForRoom(roomId)).isEqualTo(RACING_HOLDS);
  }

  /** The same room and the same three nights for every attempt, each by its own guest. */
  private List<HoldAttempt> attemptsForSameStay() {
    return IntStream.range(0, RACING_HOLDS)
        .mapToObj(
            index -> aHold().withRoomId(roomId).withStay(november(10), november(13)).attempt())
        .toList();
  }

  /** One night per attempt, one after another, so no two attempts overlap. */
  private List<HoldAttempt> attemptsForStaysOneAfterAnother() {
    return IntStream.range(0, RACING_HOLDS)
        .mapToObj(
            index ->
                aHold()
                    .withRoomId(roomId)
                    .withStay(november(10 + index), november(11 + index))
                    .attempt())
        .toList();
  }
}
