package dev.roomlock.booking.lifecycle;

import static dev.roomlock.booking.support.TestData.HOLD_TTL;
import static dev.roomlock.booking.support.TestData.NOW;
import static dev.roomlock.booking.support.TestData.aBooking;
import static dev.roomlock.booking.support.TestData.aHold;
import static dev.roomlock.booking.support.TestData.aHotel;
import static dev.roomlock.booking.support.TestData.aRoom;
import static dev.roomlock.booking.support.TestData.november;
import static org.assertj.core.api.Assertions.assertThat;

import dev.roomlock.booking.domain.Booking;
import dev.roomlock.booking.domain.BookingStatus;
import dev.roomlock.booking.domain.CancelReason;
import dev.roomlock.booking.hold.HoldResult;
import dev.roomlock.booking.hold.HoldService;
import dev.roomlock.booking.infra.BookingRepository;
import dev.roomlock.booking.infra.HotelRepository;
import dev.roomlock.booking.infra.RoomRepository;
import dev.roomlock.booking.support.BookingIntegrationTest;
import dev.roomlock.booking.support.BookingTable;
import dev.roomlock.booking.support.DatabaseCleaner;
import dev.roomlock.booking.support.MutableClock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Hold expiry: a HELD booking past its window becomes CANCELLED with reason HOLD_EXPIRED. The clock
 * is moved forward instead of waiting (rule T-7).
 */
@BookingIntegrationTest
class HoldExpiryTest {

  private static final Duration ONE_SECOND = Duration.ofSeconds(1);

  @Autowired private LifecycleService lifecycle;
  @Autowired private BookingRepository bookings;
  @Autowired private BookingTable bookingTable;
  @Autowired private HoldService holds;
  @Autowired private HotelRepository hotels;
  @Autowired private RoomRepository rooms;
  @Autowired private MutableClock clock;
  @Autowired private DatabaseCleaner databaseCleaner;

  private UUID roomId;

  @BeforeEach
  void setUp() {
    databaseCleaner.truncateAll();
    clock.setInstant(NOW);
    roomId = newRoom();
  }

  @Test
  void shouldCancelHoldWhenItsWindowHasPassed() {
    // given
    UUID bookingId = bookingTable.insert(aBooking().withRoomId(roomId));
    clock.advance(HOLD_TTL.plus(ONE_SECOND));

    // when
    int cancelled = lifecycle.expireHolds();

    // then
    assertThat(cancelled).isEqualTo(1);
    Booking booking = reload(bookingId);
    assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
    assertThat(booking.getCancelReason()).contains(CancelReason.HOLD_EXPIRED);
    assertThat(booking.getUpdatedAt()).isEqualTo(clock.instant());
  }

  @Test
  void shouldKeepHoldWhenItsWindowHasNotPassedYet() {
    // given
    UUID bookingId = bookingTable.insert(aBooking().withRoomId(roomId));
    clock.advance(HOLD_TTL.minus(ONE_SECOND));

    // when
    int cancelled = lifecycle.expireHolds();

    // then
    assertThat(cancelled).isZero();
    assertThat(bookingTable.status(bookingId)).isEqualTo("HELD");
    assertThat(reload(bookingId).getUpdatedAt()).isEqualTo(NOW);
  }

  @Test
  void shouldCancelHoldWhenItsWindowEndsExactlyNow() {
    // given
    UUID bookingId = bookingTable.insert(aBooking().withRoomId(roomId));
    clock.advance(HOLD_TTL);

    // when
    int cancelled = lifecycle.expireHolds();

    // then
    assertThat(cancelled).isEqualTo(1);
    assertThat(bookingTable.status(bookingId)).isEqualTo("CANCELLED");
  }

  @Test
  void shouldCancelOnlyTheExpiredHoldWhenAnotherHoldIsStillOpen() {
    // given
    UUID expired =
        bookingTable.insert(aBooking().withRoomId(roomId).withStay(november(10), november(13)));
    clock.advance(HOLD_TTL.plus(ONE_SECOND));
    UUID open = newHold(november(20), november(22));

    // when
    int cancelled = lifecycle.expireHolds();

    // then
    assertThat(cancelled).isEqualTo(1);
    assertThat(bookingTable.status(expired)).isEqualTo("CANCELLED");
    assertThat(bookingTable.status(open)).isEqualTo("HELD");
  }

  @Test
  void shouldLeavePayingConfirmedAndCancelledBookingsAloneWhenTheirWindowHasPassed() {
    // given
    UUID paying = insert(november(1), november(2), BookingStatus.PAYING);
    UUID confirmed = insert(november(3), november(4), BookingStatus.CONFIRMED);
    UUID cancelled = insert(november(5), november(6), BookingStatus.CANCELLED);
    clock.advance(HOLD_TTL.plus(ONE_SECOND));

    // when
    int expired = lifecycle.expireHolds();

    // then
    assertThat(expired).isZero();
    assertThat(bookingTable.status(paying)).isEqualTo("PAYING");
    assertThat(bookingTable.status(confirmed)).isEqualTo("CONFIRMED");
    assertThat(bookingTable.status(cancelled)).isEqualTo("CANCELLED");
    assertThat(reload(cancelled).getCancelReason()).contains(CancelReason.USER_CANCELLED);
  }

  @Test
  void shouldCancelOneBatchOnlyWhenMoreHoldsExpired() {
    // given
    insert(november(10), november(13), BookingStatus.HELD);
    insert(november(20), november(22), BookingStatus.HELD);
    clock.advance(HOLD_TTL.plus(ONE_SECOND));

    // when
    int cancelled = bookings.expireHolds(clock.instant(), 1);

    // then
    assertThat(cancelled).isEqualTo(1);
    assertThat(bookings.expireHolds(clock.instant(), 1)).isEqualTo(1);
    assertThat(bookings.expireHolds(clock.instant(), 1)).isZero();
  }

  @Test
  void shouldHoldRoomAgainWhenExpiredHoldWasCancelled() {
    // given
    newHold(november(10), november(13));
    clock.advance(HOLD_TTL.plus(ONE_SECOND));
    assertThat(lifecycle.expireHolds()).isEqualTo(1);

    // when
    HoldResult result =
        holds.hold(aHold().withRoomId(roomId).withStay(november(10), november(13)).build());

    // then
    assertThat(result).isInstanceOf(HoldResult.Held.class);
    assertThat(bookingTable.countForRoom(roomId)).isEqualTo(2);
  }

  private UUID insert(LocalDate checkIn, LocalDate checkOut, BookingStatus status) {
    return bookingTable.insert(
        aBooking().withRoomId(roomId).withStay(checkIn, checkOut).withStatus(status));
  }

  private UUID newHold(LocalDate checkIn, LocalDate checkOut) {
    HoldResult result = holds.hold(aHold().withRoomId(roomId).withStay(checkIn, checkOut).build());
    return ((HoldResult.Held) result).booking().getId();
  }

  private Booking reload(UUID bookingId) {
    return bookings.findById(bookingId).orElseThrow();
  }

  private UUID newRoom() {
    UUID hotelId = hotels.save(aHotel().build()).getId();
    return rooms.save(aRoom().withHotelId(hotelId).build()).getId();
  }
}
