package dev.roomlock.booking.lifecycle;

import static dev.roomlock.booking.support.TestData.NOW;
import static dev.roomlock.booking.support.TestData.aBooking;
import static dev.roomlock.booking.support.TestData.aHold;
import static dev.roomlock.booking.support.TestData.aHotel;
import static dev.roomlock.booking.support.TestData.aRoom;
import static dev.roomlock.booking.support.TestData.november;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import dev.roomlock.booking.domain.Booking;
import dev.roomlock.booking.domain.BookingStatus;
import dev.roomlock.booking.domain.CancelReason;
import dev.roomlock.booking.domain.ResourceNotFoundException;
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
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Guest cancellation: a HELD booking becomes CANCELLED with reason USER_CANCELLED. */
@BookingIntegrationTest
class CancelBookingTest {

  private static final Duration ONE_MINUTE = Duration.ofMinutes(1);

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
  void shouldCancelBookingWhenGuestAsksForIt() {
    // given
    UUID bookingId = bookingTable.insert(aBooking().withRoomId(roomId));
    clock.advance(ONE_MINUTE);

    // when
    CancelResult result = cancelAsOwner(bookingId);

    // then
    assertThat(result).isInstanceOf(CancelResult.Cancelled.class);
    Booking booking = ((CancelResult.Cancelled) result).booking();
    assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
    assertThat(booking.getCancelReason()).contains(CancelReason.USER_CANCELLED);
    assertThat(booking.getUpdatedAt()).isEqualTo(clock.instant());
    assertThat(bookingTable.status(bookingId)).isEqualTo("CANCELLED");
  }

  @Test
  void shouldReportAlreadyCancelledWhenCancelIsRepeated() {
    // given
    UUID bookingId = bookingTable.insert(aBooking().withRoomId(roomId));
    clock.advance(ONE_MINUTE);
    cancelAsOwner(bookingId);
    Instant cancelledAt = clock.instant();
    clock.advance(ONE_MINUTE);

    // when
    CancelResult result = cancelAsOwner(bookingId);

    // then
    assertThat(result).isInstanceOf(CancelResult.AlreadyCancelled.class);
    Booking booking = ((CancelResult.AlreadyCancelled) result).booking();
    assertThat(booking.getCancelReason()).contains(CancelReason.USER_CANCELLED);
    assertThat(booking.getUpdatedAt()).isEqualTo(cancelledAt);
  }

  @Test
  void shouldKeepHoldExpiredReasonWhenExpiredBookingIsCancelled() {
    // given
    UUID bookingId =
        bookingTable.insert(
            aBooking()
                .withRoomId(roomId)
                .withStatus(BookingStatus.CANCELLED)
                .withCancelReason(CancelReason.HOLD_EXPIRED));

    // when
    CancelResult result = cancelAsOwner(bookingId);

    // then
    assertThat(result).isInstanceOf(CancelResult.AlreadyCancelled.class);
    assertThat(reload(bookingId).getCancelReason()).contains(CancelReason.HOLD_EXPIRED);
  }

  /** A paid stay may be dropped now that payment-service refunds it (rule K-1). */
  @Test
  void shouldCancelBookingWhenItWasAlreadyConfirmed() {
    // given
    UUID bookingId =
        bookingTable.insert(aBooking().withRoomId(roomId).withStatus(BookingStatus.CONFIRMED));
    clock.advance(ONE_MINUTE);

    // when
    CancelResult result = cancelAsOwner(bookingId);

    // then
    assertThat(result).isInstanceOf(CancelResult.Cancelled.class);
    Booking booking = ((CancelResult.Cancelled) result).booking();
    assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
    assertThat(booking.getCancelReason()).contains(CancelReason.USER_CANCELLED);
    assertThat(bookingTable.status(bookingId)).isEqualTo("CANCELLED");
    assertThat(booking.getUpdatedAt()).isEqualTo(clock.instant());
  }

  @Test
  void shouldHoldRoomAgainWhenConfirmedBookingWasCancelled() {
    // given
    UUID bookingId =
        bookingTable.insert(aBooking().withRoomId(roomId).withStatus(BookingStatus.CONFIRMED));
    cancelAsOwner(bookingId);

    // when
    HoldResult result =
        holds.hold(aHold().withRoomId(roomId).withStay(november(10), november(13)).build());

    // then
    assertThat(result).isInstanceOf(HoldResult.Held.class);
  }

  @Test
  void shouldRefuseCancelWhenBookingIsPaying() {
    // given
    UUID bookingId =
        bookingTable.insert(aBooking().withRoomId(roomId).withStatus(BookingStatus.PAYING));

    // when
    CancelResult result = cancelAsOwner(bookingId);

    // then
    assertThat(result).isEqualTo(new CancelResult.NotCancellable(bookingId, BookingStatus.PAYING));
    assertThat(bookingTable.status(bookingId)).isEqualTo("PAYING");
  }

  @Test
  void shouldFailWithNotFoundWhenBookingIsUnknown() {
    // given
    UUID unknownId = UUID.randomUUID();

    // when
    // then
    assertThatExceptionOfType(ResourceNotFoundException.class)
        .isThrownBy(() -> lifecycle.cancel(unknownId, UUID.randomUUID()))
        .satisfies(
            thrown -> {
              assertThat(thrown.resource()).isEqualTo("booking");
              assertThat(thrown.id()).isEqualTo(unknownId);
            });
  }

  @Test
  void shouldHoldRoomAgainWhenGuestCancelledTheBooking() {
    // given
    HoldResult held =
        holds.hold(aHold().withRoomId(roomId).withStay(november(10), november(13)).build());
    Booking heldBooking = ((HoldResult.Held) held).booking();
    lifecycle.cancel(heldBooking.getId(), heldBooking.getGuestId());

    // when
    HoldResult result =
        holds.hold(aHold().withRoomId(roomId).withStay(november(10), november(13)).build());

    // then
    assertThat(result).isInstanceOf(HoldResult.Held.class);
    assertThat(bookingTable.countForRoom(roomId)).isEqualTo(2);
  }

  private Booking reload(UUID bookingId) {
    return bookings.findById(bookingId).orElseThrow();
  }

  /** The cancel of the guest the booking belongs to; ownership itself is covered end to end. */
  private CancelResult cancelAsOwner(UUID bookingId) {
    return lifecycle.cancel(bookingId, bookingTable.guestId(bookingId));
  }

  private UUID newRoom() {
    UUID hotelId = hotels.save(aHotel().build()).getId();
    return rooms.save(aRoom().withHotelId(hotelId).build()).getId();
  }
}
