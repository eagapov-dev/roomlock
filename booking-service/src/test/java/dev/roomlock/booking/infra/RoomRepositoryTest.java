package dev.roomlock.booking.infra;

import static dev.roomlock.booking.support.TestData.aHotel;
import static dev.roomlock.booking.support.TestData.aRoom;
import static org.assertj.core.api.Assertions.assertThat;

import dev.roomlock.booking.domain.Room;
import dev.roomlock.booking.support.BookingJpaTest;
import dev.roomlock.booking.support.DatabaseCleaner;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@BookingJpaTest
class RoomRepositoryTest {

  @Autowired private DatabaseCleaner databaseCleaner;
  @Autowired private HotelRepository hotels;
  @Autowired private RoomRepository rooms;

  @BeforeEach
  void setUp() {
    databaseCleaner.truncateAll();
  }

  @Test
  void shouldReturnOnlyRoomsOfHotelWhenFindingByHotelId() {
    // given
    UUID hotelId = hotels.save(aHotel().build()).getId();
    UUID otherHotelId = hotels.save(aHotel().withName("Hotel Bristol").build()).getId();
    UUID first = rooms.save(aRoom().withHotelId(hotelId).withNumber("101").build()).getId();
    UUID second = rooms.save(aRoom().withHotelId(hotelId).withNumber("102").build()).getId();
    rooms.save(aRoom().withHotelId(otherHotelId).withNumber("101").build());

    // when
    List<Room> found = rooms.findByHotelId(hotelId);

    // then
    assertThat(found).extracting(Room::getId).containsExactlyInAnyOrder(first, second);
  }
}
