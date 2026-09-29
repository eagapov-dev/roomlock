package dev.roomlock.booking.infra;

import static dev.roomlock.booking.support.TestData.aBooking;
import static dev.roomlock.booking.support.TestData.aHotel;
import static dev.roomlock.booking.support.TestData.aRoom;
import static org.assertj.core.api.Assertions.assertThat;

import dev.roomlock.booking.domain.Booking;
import dev.roomlock.booking.support.BookingJpaTest;
import dev.roomlock.booking.support.BookingTable;
import dev.roomlock.booking.support.DatabaseCleaner;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@BookingJpaTest
class BookingRepositoryTest {

  @Autowired private DatabaseCleaner databaseCleaner;
  @Autowired private BookingTable bookingTable;
  @Autowired private BookingRepository bookings;
  @Autowired private HotelRepository hotels;
  @Autowired private RoomRepository rooms;

  private UUID roomId;

  @BeforeEach
  void setUp() {
    databaseCleaner.truncateAll();
    UUID hotelId = hotels.save(aHotel().build()).getId();
    roomId = rooms.save(aRoom().withHotelId(hotelId).build()).getId();
  }

  @Test
  void shouldStoreStatusAsItsNameWhenBookingIsSavedThroughJpa() {
    // given
    Booking held = aBooking().withRoomId(roomId).build();

    // when
    UUID id = bookings.save(held).getId();

    // then
    assertThat(bookingTable.status(id)).isEqualTo("HELD");
  }
}
