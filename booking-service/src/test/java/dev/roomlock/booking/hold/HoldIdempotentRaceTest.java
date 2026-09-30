package dev.roomlock.booking.hold;

import static dev.roomlock.booking.support.TestData.HOLD_TTL;
import static dev.roomlock.booking.support.TestData.aHold;
import static dev.roomlock.booking.support.TestData.aHotel;
import static dev.roomlock.booking.support.TestData.aRoom;
import static dev.roomlock.booking.support.TestData.november;
import static org.assertj.core.api.Assertions.assertThat;

import dev.roomlock.booking.domain.Booking;
import dev.roomlock.booking.infra.BookingRepository;
import dev.roomlock.booking.infra.HotelRepository;
import dev.roomlock.booking.infra.RoomRepository;
import dev.roomlock.booking.support.BookingIntegrationTest;
import dev.roomlock.booking.support.BookingTable;
import dev.roomlock.booking.support.Concurrently;
import dev.roomlock.booking.support.DatabaseCleaner;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;

/**
 * A client that retries a hold while the first request is still in flight (rule I-1): both requests
 * carry the same guest and the same {@code Idempotency-Key}, both reach the insert, and the loser
 * has to be answered with the booking the winner made.
 *
 * <p>The loser's row breaks {@code booking_guest_id_idempotency_key_key} and {@code
 * booking_no_overlap} at once, and PostgreSQL reports one of the two - which one depends on the
 * order it maintains the indexes in. So the loss is scripted: the two tests below hand the use case
 * the winner plus each of the answers a strategy can read out of that error, and both have to end
 * in the same replay. A test that raced for it would only ever pin the code this server happens to
 * pick.
 */
@BookingIntegrationTest
class HoldIdempotentRaceTest {

  private static final int RACING_RETRIES = 8;

  @Autowired private RoomRepository rooms;
  @Autowired private BookingRepository bookings;
  @Autowired private HotelRepository hotels;
  @Autowired private BookingTable table;
  @Autowired private DatabaseCleaner databaseCleaner;
  @Autowired private Clock clock;
  @Autowired private HoldService configuredHolds;

  private UUID roomId;

  @BeforeEach
  void setUp() {
    databaseCleaner.truncateAll();
    UUID hotelId = hotels.save(aHotel().build()).getId();
    roomId = rooms.save(aRoom().withHotelId(hotelId).build()).getId();
  }

  @Test
  void shouldReplayTheWinnerWhenSimultaneousRetryIsReportedBusy() {
    // given the room is taken by a retry of this very request, reported as an overlap
    LostToItsOwnRetry strategy = new LostToItsOwnRetry(new HoldOutcome.Busy());
    HoldService holds = holdsWith(strategy);

    // when
    HoldResult result = holds.hold(aHold().withRoomId(roomId).build());

    // then
    assertThat(result).isInstanceOf(HoldResult.AlreadyHeld.class);
    assertThat(((HoldResult.AlreadyHeld) result).booking().getId()).isEqualTo(strategy.winnerId());
    assertThat(table.countForRoom(roomId)).isEqualTo(1);
  }

  @Test
  void shouldReplayTheWinnerWhenSimultaneousRetryIsReportedKeyTaken() {
    // given the same race, reported as the taken idempotency key it also is
    LostToItsOwnRetry strategy = new LostToItsOwnRetry(new HoldOutcome.KeyTaken());
    HoldService holds = holdsWith(strategy);

    // when
    HoldResult result = holds.hold(aHold().withRoomId(roomId).build());

    // then
    assertThat(result).isInstanceOf(HoldResult.AlreadyHeld.class);
    assertThat(((HoldResult.AlreadyHeld) result).booking().getId()).isEqualTo(strategy.winnerId());
    assertThat(table.countForRoom(roomId)).isEqualTo(1);
  }

  @Test
  void shouldReportRoomBusyWhenTheRoomIsTakenByAnotherGuest() {
    // given nobody used this idempotency key, so the overlap is somebody else's booking
    HoldService holds = holdsWith(new Reporting(new HoldOutcome.Busy()));
    HoldCommand command = aHold().withRoomId(roomId).build();

    // when
    HoldResult result = holds.hold(command);

    // then
    assertThat(result).isEqualTo(new HoldResult.RoomBusy(roomId, command.stay()));
  }

  /**
   * The same race against the configured strategy and a real constraint. Deterministic in what it
   * asserts whichever way the retries interleave: the key admits one row, so one request holds and
   * every other one is either replayed that booking or told to try again - never that the room is
   * busy, which is the answer this room can no longer give its own guest.
   */
  @Test
  void shouldNeverAnswerRoomBusyWhenRetriesOfOneKeyRaceForTheRoom() {
    // given one hold, sent RACING_RETRIES times at once
    HoldCommand command = aHold().withRoomId(roomId).withStay(november(10), november(13)).build();

    // when
    List<HoldResult> results =
        Concurrently.run(RACING_RETRIES, index -> configuredHolds.hold(command));

    // then
    assertThat(results).filteredOn(HoldResult.Held.class::isInstance).hasSize(1);
    assertThat(results).noneMatch(HoldResult.RoomBusy.class::isInstance);
    assertThat(table.countForRoom(roomId)).isEqualTo(1);
    UUID heldId = heldBookingId(results);
    assertThat(results)
        .filteredOn(HoldResult.AlreadyHeld.class::isInstance)
        .allMatch(replay -> ((HoldResult.AlreadyHeld) replay).booking().getId().equals(heldId));
  }

  private HoldService holdsWith(HoldStrategy strategy) {
    return new HoldService(
        rooms, bookings, strategiesOf(strategy), new HoldProperties(HOLD_TTL), clock);
  }

  private static HoldStrategies strategiesOf(HoldStrategy strategy) {
    return new HoldStrategies(
        List.of(strategy),
        new LockingProperties(strategy.strategy(), new LockingProperties.Optimistic(1)));
  }

  private static UUID heldBookingId(List<HoldResult> results) {
    return results.stream()
        .filter(HoldResult.Held.class::isInstance)
        .map(held -> ((HoldResult.Held) held).booking().getId())
        .findFirst()
        .orElseThrow(() -> new AssertionError("No request held the room"));
  }

  /** A strategy that reports what it was given and writes nothing. */
  private record Reporting(HoldOutcome reported) implements HoldStrategy {

    @Override
    public LockingStrategy strategy() {
      return LockingStrategy.CONSTRAINT;
    }

    @Override
    public HoldOutcome hold(HoldAttempt attempt) {
      return reported;
    }
  }

  /**
   * A strategy whose attempt loses to a simultaneous retry of itself: it commits the row that retry
   * would have written and then reports the scripted answer, as the database reports it to a loser.
   */
  private final class LostToItsOwnRetry implements HoldStrategy {

    private final HoldOutcome reported;
    @Nullable private Booking winner;

    LostToItsOwnRetry(HoldOutcome reported) {
      this.reported = reported;
    }

    /** Id of the booking the winning retry left behind. */
    UUID winnerId() {
      return Objects.requireNonNull(winner, "No attempt has run yet").getId();
    }

    @Override
    public LockingStrategy strategy() {
      return LockingStrategy.CONSTRAINT;
    }

    @Override
    public HoldOutcome hold(HoldAttempt attempt) {
      winner = bookings.saveAndFlush(attempt.newBooking());
      return reported;
    }
  }
}
