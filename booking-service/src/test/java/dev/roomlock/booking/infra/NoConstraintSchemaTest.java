package dev.roomlock.booking.infra;

import static dev.roomlock.booking.support.TestData.aBooking;
import static dev.roomlock.booking.support.TestData.aHotel;
import static dev.roomlock.booking.support.TestData.aRoom;
import static dev.roomlock.booking.support.TestData.november;
import static org.assertj.core.api.Assertions.assertThat;

import dev.roomlock.booking.Profiles;
import dev.roomlock.booking.support.BookingJpaTest;
import dev.roomlock.booking.support.BookingTable;
import dev.roomlock.booking.support.DatabaseCleaner;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

/** Profile no-constraint: Flyway skips db/constraint, so the overlap guard is absent (D1). */
@BookingJpaTest
@ActiveProfiles({Profiles.TEST, Profiles.NO_CONSTRAINT})
class NoConstraintSchemaTest {

  @Autowired private DatabaseCleaner databaseCleaner;
  @Autowired private BookingTable bookings;
  @Autowired private HotelRepository hotels;
  @Autowired private RoomRepository rooms;
  @Autowired private Flyway flyway;
  @Autowired private Environment environment;

  private UUID roomId;

  @BeforeEach
  void setUp() {
    databaseCleaner.truncateAll();
    UUID hotelId = hotels.save(aHotel().build()).getId();
    roomId = rooms.save(aRoom().withHotelId(hotelId).build()).getId();
  }

  @Test
  void shouldInsertOverlappingHeldBookingWhenConstraintLocationIsSkipped() {
    // given
    bookings.insert(aBooking().withRoomId(roomId).withStay(november(10), november(13)));

    // when
    UUID id = bookings.insert(aBooking().withRoomId(roomId).withStay(november(12), november(14)));

    // then
    assertThat(bookings.status(id)).isEqualTo("HELD");
  }

  @Test
  void shouldValidateEntitiesAgainstSchemaWithoutConstraintWhenContextStarts() {
    // given the context is up: Hibernate ran ddl-auto=validate against the migrated schema

    // when
    String ddlAuto = environment.getProperty("spring.jpa.hibernate.ddl-auto");

    // then
    assertThat(ddlAuto).isEqualTo("validate");
    assertThat(flyway.info().applied()).isNotEmpty();
    assertThat(flyway.info().pending()).isEmpty();
  }
}
