package dev.roomlock.booking.hold;

import static dev.roomlock.booking.support.TestData.HOLD_TTL;
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
import dev.roomlock.booking.infra.HotelRepository;
import dev.roomlock.booking.infra.RoomRepository;
import dev.roomlock.booking.support.BookingIntegrationTest;
import dev.roomlock.booking.support.BookingTable;
import dev.roomlock.booking.support.DatabaseCleaner;
import dev.roomlock.booking.support.TestData.HoldDraft;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The hold use case: price, hold window, idempotency and a busy room (task 2.1). */
@BookingIntegrationTest
class HoldServiceTest {

  @Autowired private HoldService holds;
  @Autowired private BookingTable bookings;
  @Autowired private DatabaseCleaner databaseCleaner;
  @Autowired private HotelRepository hotels;
  @Autowired private RoomRepository rooms;

  private UUID roomId;

  @BeforeEach
  void setUp() {
    databaseCleaner.truncateAll();
    roomId = newRoom();
  }

  @Test
  void shouldHoldRoomForEveryNightOfStayWhenRoomIsFree() {
    // given
    HoldDraft draft = aHold().withRoomId(roomId).withStay(november(10), november(13));

    // when
    HoldResult result = holds.hold(draft.build());

    // then
    assertThat(result).isInstanceOf(HoldResult.Held.class);
    Booking booking = ((HoldResult.Held) result).booking();
    assertThat(booking.getStatus()).isEqualTo(BookingStatus.HELD);
    assertThat(booking.getGuestId()).isEqualTo(draft.guestId());
    assertThat(booking.getTotalPrice()).isEqualByComparingTo(new BigDecimal("450.00"));
    assertThat(booking.getHoldExpiresAt()).isEqualTo(NOW.plus(HOLD_TTL));
    assertThat(bookings.stay(booking.getId())).isEqualTo("[2026-11-10,2026-11-13)");
  }

  @Test
  void shouldReturnTheSameBookingWhenRequestIsRetriedWithSameIdempotencyKey() {
    // given
    HoldDraft draft = aHold().withRoomId(roomId);
    Booking held = ((HoldResult.Held) holds.hold(draft.build())).booking();

    // when
    HoldResult retry = holds.hold(draft.build());

    // then
    assertThat(retry).isInstanceOf(HoldResult.AlreadyHeld.class);
    assertThat(((HoldResult.AlreadyHeld) retry).booking().getId()).isEqualTo(held.getId());
    assertThat(bookings.countForRoom(roomId)).isEqualTo(1);
  }

  @Test
  void shouldReportKeyReusedWhenSameKeyCarriesAnotherRequest() {
    // given
    HoldDraft draft = aHold().withRoomId(roomId).withStay(november(10), november(13));
    holds.hold(draft.build());

    // when
    HoldResult result = holds.hold(draft.withStay(november(20), november(22)).build());

    // then
    assertThat(result).isEqualTo(new HoldResult.IdempotencyKeyReused(draft.idempotencyKey()));
    assertThat(bookings.countForRoom(roomId)).isEqualTo(1);
  }

  @Test
  void shouldHoldWhenAnotherGuestUsesTheSameIdempotencyKey() {
    // given
    HoldDraft draft = aHold().withRoomId(roomId);
    holds.hold(draft.build());
    UUID otherRoomId = newRoom();

    // when
    HoldResult result =
        holds.hold(draft.withGuestId(UUID.randomUUID()).withRoomId(otherRoomId).build());

    // then
    assertThat(result).isInstanceOf(HoldResult.Held.class);
  }

  @Test
  void shouldReportRoomBusyWhenStayOverlapsActiveBooking() {
    // given
    holds.hold(aHold().withRoomId(roomId).withStay(november(10), november(13)).build());
    HoldDraft overlapping = aHold().withRoomId(roomId).withStay(november(12), november(14));

    // when
    HoldResult result = holds.hold(overlapping.build());

    // then
    assertThat(result).isEqualTo(new HoldResult.RoomBusy(roomId, overlapping.build().stay()));
    assertThat(bookings.countForRoom(roomId)).isEqualTo(1);
  }

  @Test
  void shouldHoldWhenStayStartsOnCheckOutOfExistingBooking() {
    // given
    holds.hold(aHold().withRoomId(roomId).withStay(november(10), november(13)).build());

    // when
    HoldResult result =
        holds.hold(aHold().withRoomId(roomId).withStay(november(13), november(15)).build());

    // then
    assertThat(result).isInstanceOf(HoldResult.Held.class);
  }

  @Test
  void shouldHoldWhenOverlappingBookingIsCancelled() {
    // given
    bookings.insert(
        aBooking()
            .withRoomId(roomId)
            .withStay(november(10), november(13))
            .withStatus(BookingStatus.CANCELLED)
            .withCancelReason(CancelReason.HOLD_EXPIRED));

    // when
    HoldResult result =
        holds.hold(aHold().withRoomId(roomId).withStay(november(10), november(13)).build());

    // then
    assertThat(result).isInstanceOf(HoldResult.Held.class);
  }

  @Test
  void shouldFailWithNotFoundWhenRoomIsUnknown() {
    // given
    UUID unknownRoomId = UUID.randomUUID();

    // when
    // then
    assertThatExceptionOfType(ResourceNotFoundException.class)
        .isThrownBy(() -> holds.hold(aHold().withRoomId(unknownRoomId).build()))
        .satisfies(
            thrown -> {
              assertThat(thrown.resource()).isEqualTo("room");
              assertThat(thrown.id()).isEqualTo(unknownRoomId);
            });
  }

  @Test
  void shouldReadBookingBackWhenItWasHeld() {
    // given
    Booking held = ((HoldResult.Held) holds.hold(aHold().withRoomId(roomId).build())).booking();

    // when
    Booking found = holds.getBooking(held.getId());

    // then
    assertThat(found.getId()).isEqualTo(held.getId());
    assertThat(found.getStatus()).isEqualTo(BookingStatus.HELD);
  }

  @Test
  void shouldFailWithNotFoundWhenBookingIsUnknown() {
    // given
    UUID unknownId = UUID.randomUUID();

    // when
    // then
    assertThatExceptionOfType(ResourceNotFoundException.class)
        .isThrownBy(() -> holds.getBooking(unknownId))
        .satisfies(thrown -> assertThat(thrown.resource()).isEqualTo("booking"));
  }

  private UUID newRoom() {
    UUID hotelId = hotels.save(aHotel().build()).getId();
    return rooms.save(aRoom().withHotelId(hotelId).build()).getId();
  }
}
