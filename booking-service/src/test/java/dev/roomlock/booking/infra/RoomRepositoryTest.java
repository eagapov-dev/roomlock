package dev.roomlock.booking.infra;

import static dev.roomlock.booking.support.TestData.aHotel;
import static dev.roomlock.booking.support.TestData.aRoom;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.roomlock.booking.domain.Room;
import dev.roomlock.booking.support.BookingJpaTest;
import dev.roomlock.booking.support.DatabaseCleaner;
import dev.roomlock.booking.support.PostgresErrors;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@BookingJpaTest
class RoomRepositoryTest {

  private static final String ROOM_NUMBER_CONSTRAINT = "room_hotel_id_number_key";

  @Autowired private DatabaseCleaner databaseCleaner;
  @Autowired private HotelRepository hotels;
  @Autowired private RoomRepository rooms;
  @Autowired private PlatformTransactionManager transactionManager;

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

  @Test
  void shouldRejectWithUniqueViolationWhenRoomNumberRepeatsWithinHotel() {
    // given
    UUID hotelId = hotels.save(aHotel().build()).getId();
    rooms.save(aRoom().withHotelId(hotelId).withNumber("101").build());

    // when
    Throwable thrown =
        catchThrowable(
            () -> rooms.saveAndFlush(aRoom().withHotelId(hotelId).withNumber("101").build()));

    // then
    assertThat(PostgresErrors.sqlState(thrown)).contains(PostgresErrors.UNIQUE_VIOLATION);
    assertThat(PostgresErrors.constraintName(thrown)).contains(ROOM_NUMBER_CONSTRAINT);
  }

  @Test
  void shouldInsertNothingWhenRoomNumberIsTakenInHotel() {
    // given
    UUID hotelId = hotels.save(aHotel().build()).getId();
    UUID existing = rooms.save(aRoom().withHotelId(hotelId).withNumber("101").build()).getId();
    Room duplicate = aRoom().withHotelId(hotelId).withNumber("101").build();

    // when
    Integer inserted = insertIfNumberFree(duplicate);

    // then
    assertThat(inserted).isZero();
    assertThat(rooms.findByHotelId(hotelId)).extracting(Room::getId).containsExactly(existing);
  }

  @Test
  void shouldInsertRoomWhenNumberIsTakenOnlyInAnotherHotel() {
    // given
    UUID hotelId = hotels.save(aHotel().build()).getId();
    UUID otherHotelId = hotels.save(aHotel().withName("Hotel Bristol").build()).getId();
    rooms.save(aRoom().withHotelId(otherHotelId).withNumber("101").build());
    Room room = aRoom().withHotelId(hotelId).withNumber("101").build();

    // when
    Integer inserted = insertIfNumberFree(room);

    // then
    assertThat(inserted).isEqualTo(1);
    Room stored = rooms.findById(room.getId()).orElseThrow();
    assertThat(stored.getHotelId()).isEqualTo(hotelId);
    assertThat(stored.getNumber()).isEqualTo("101");
    assertThat(stored.getCapacity()).isEqualTo(room.getCapacity());
    assertThat(stored.getPricePerNight()).isEqualByComparingTo(room.getPricePerNight());
    assertThat(stored.getVersion()).isZero();
  }

  /** The query is a write: it runs inside a transaction, as the catalog service will call it. */
  private Integer insertIfNumberFree(Room room) {
    return new TransactionTemplate(transactionManager)
        .execute(status -> rooms.insertIfNumberFree(room));
  }
}
