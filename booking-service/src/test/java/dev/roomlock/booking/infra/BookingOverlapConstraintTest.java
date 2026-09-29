package dev.roomlock.booking.infra;

import static dev.roomlock.booking.support.TestData.aBooking;
import static dev.roomlock.booking.support.TestData.aHotel;
import static dev.roomlock.booking.support.TestData.aRoom;
import static dev.roomlock.booking.support.TestData.november;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.roomlock.booking.domain.BookingStatus;
import dev.roomlock.booking.support.BookingJpaTest;
import dev.roomlock.booking.support.BookingTable;
import dev.roomlock.booking.support.DatabaseCleaner;
import dev.roomlock.booking.support.PostgresErrors;
import dev.roomlock.booking.support.TestData.BookingDraft;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;

/** Exclusion constraint booking_no_overlap from db/constraint (spec «Доменная модель», D1). */
@BookingJpaTest
class BookingOverlapConstraintTest {

  @Autowired private DatabaseCleaner databaseCleaner;
  @Autowired private BookingTable bookings;
  @Autowired private HotelRepository hotels;
  @Autowired private RoomRepository rooms;

  private UUID roomId;

  @BeforeEach
  void setUp() {
    databaseCleaner.truncateAll();
    roomId = newRoom();
  }

  @Test
  void shouldRejectWithExclusionViolationWhenHeldBookingOverlapsHeldBooking() {
    // given
    bookings.insert(heldOnRoom().withStay(november(10), november(13)));
    BookingDraft overlapping = heldOnRoom().withStay(november(12), november(14));

    // when
    Throwable thrown = catchThrowable(() -> bookings.insert(overlapping));

    // then
    assertThat(PostgresErrors.sqlState(thrown)).contains(PostgresErrors.EXCLUSION_VIOLATION);
  }

  @Test
  void shouldInsertWhenCheckInEqualsCheckOutOfExistingBooking() {
    // given
    bookings.insert(heldOnRoom().withStay(november(10), november(13)));
    BookingDraft adjacent = heldOnRoom().withStay(november(13), november(15));

    // when
    UUID id = bookings.insert(adjacent);

    // then
    assertThat(bookings.status(id)).isEqualTo("HELD");
  }

  @Test
  void shouldInsertWhenOverlappingBookingIsCancelled() {
    // given
    bookings.insert(
        heldOnRoom().withStay(november(10), november(13)).withStatus(BookingStatus.CANCELLED));
    BookingDraft sameDates = heldOnRoom().withStay(november(10), november(13));

    // when
    UUID id = bookings.insert(sameDates);

    // then
    assertThat(bookings.status(id)).isEqualTo("HELD");
  }

  @ParameterizedTest
  @EnumSource(
      value = BookingStatus.class,
      names = {"PAYING", "CONFIRMED"})
  void shouldRejectWithExclusionViolationWhenExistingBookingIsActive(BookingStatus status) {
    // given
    bookings.insert(heldOnRoom().withStay(november(10), november(13)).withStatus(status));
    BookingDraft overlapping = heldOnRoom().withStay(november(12), november(14));

    // when
    Throwable thrown = catchThrowable(() -> bookings.insert(overlapping));

    // then
    assertThat(PostgresErrors.sqlState(thrown)).contains(PostgresErrors.EXCLUSION_VIOLATION);
  }

  @Test
  void shouldInsertWhenSameDatesAreBookedForAnotherRoom() {
    // given
    bookings.insert(heldOnRoom().withStay(november(10), november(13)));
    BookingDraft otherRoom = aBooking().withRoomId(newRoom()).withStay(november(10), november(13));

    // when
    UUID id = bookings.insert(otherRoom);

    // then
    assertThat(bookings.status(id)).isEqualTo("HELD");
  }

  private BookingDraft heldOnRoom() {
    return aBooking().withRoomId(roomId);
  }

  private UUID newRoom() {
    UUID hotelId = hotels.save(aHotel().build()).getId();
    return rooms.save(aRoom().withHotelId(hotelId).build()).getId();
  }
}
