package dev.roomlock.booking.infra;

import static dev.roomlock.booking.support.TestData.aBooking;
import static dev.roomlock.booking.support.TestData.aHotel;
import static dev.roomlock.booking.support.TestData.aRoom;
import static dev.roomlock.booking.support.TestData.november;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.roomlock.booking.domain.BookingStatus;
import dev.roomlock.booking.domain.CancelReason;
import dev.roomlock.booking.support.BookingJpaTest;
import dev.roomlock.booking.support.BookingTable;
import dev.roomlock.booking.support.DatabaseCleaner;
import dev.roomlock.booking.support.PostgresErrors;
import dev.roomlock.booking.support.TestData.BookingDraft;
import java.time.LocalDate;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

/** Booking table DDL: CHECK, idempotency uniqueness, generated stay, schema validation. */
@BookingJpaTest
class BookingSchemaTest {

  private static final String IDEMPOTENCY_CONSTRAINT = "booking_guest_id_idempotency_key_key";

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

  @ParameterizedTest
  @ValueSource(ints = {0, -1})
  void shouldRejectWithCheckViolationWhenCheckOutIsNotAfterCheckIn(int nightsAfterCheckIn) {
    // given
    LocalDate checkIn = november(10);
    BookingDraft invalid =
        aBooking().withRoomId(roomId).withStay(checkIn, checkIn.plusDays(nightsAfterCheckIn));

    // when
    Throwable thrown = catchThrowable(() -> bookings.insert(invalid));

    // then
    assertThat(PostgresErrors.sqlState(thrown)).contains(PostgresErrors.CHECK_VIOLATION);
  }

  @Test
  void shouldRejectWithCheckViolationWhenCancelledBookingHasNoReason() {
    // given
    BookingDraft cancelledWithoutReason =
        aBooking().withRoomId(roomId).withStatus(BookingStatus.CANCELLED).withCancelReason(null);

    // when
    Throwable thrown = catchThrowable(() -> bookings.insert(cancelledWithoutReason));

    // then
    assertThat(PostgresErrors.sqlState(thrown)).contains(PostgresErrors.CHECK_VIOLATION);
  }

  @Test
  void shouldRejectWithCheckViolationWhenActiveBookingHasReason() {
    // given
    BookingDraft heldWithReason =
        aBooking().withRoomId(roomId).withCancelReason(CancelReason.USER_CANCELLED);

    // when
    Throwable thrown = catchThrowable(() -> bookings.insert(heldWithReason));

    // then
    assertThat(PostgresErrors.sqlState(thrown)).contains(PostgresErrors.CHECK_VIOLATION);
  }

  @Test
  void shouldRejectWithUniqueViolationWhenGuestReusesIdempotencyKey() {
    // given
    BookingDraft first = aBooking().withRoomId(roomId).withStay(november(10), november(13));
    bookings.insert(first);
    BookingDraft retry = first.withNewId().withStay(november(20), november(22));

    // when
    Throwable thrown = catchThrowable(() -> bookings.insert(retry));

    // then
    assertThat(PostgresErrors.sqlState(thrown)).contains(PostgresErrors.UNIQUE_VIOLATION);
    assertThat(PostgresErrors.constraintName(thrown)).contains(IDEMPOTENCY_CONSTRAINT);
  }

  @Test
  void shouldInsertWhenAnotherGuestUsesSameIdempotencyKey() {
    // given
    BookingDraft first = aBooking().withRoomId(roomId).withStay(november(10), november(13));
    bookings.insert(first);
    BookingDraft otherGuest =
        first.withNewId().withGuestId(UUID.randomUUID()).withStay(november(20), november(22));

    // when
    UUID id = bookings.insert(otherGuest);

    // then
    assertThat(bookings.status(id)).isEqualTo("HELD");
  }

  @Test
  void shouldGenerateHalfOpenStayWhenBookingIsInserted() {
    // given
    LocalDate checkIn = november(10);
    LocalDate checkOut = november(13);
    BookingDraft booking = aBooking().withRoomId(roomId).withStay(checkIn, checkOut);

    // when
    UUID id = bookings.insert(booking);

    // then
    assertThat(bookings.stay(id)).isEqualTo("[" + checkIn + "," + checkOut + ")");
  }

  @Test
  void shouldValidateEntitiesAgainstFullSchemaWhenContextStarts() {
    // given the context is up: Hibernate ran ddl-auto=validate against the migrated schema

    // when
    String ddlAuto = environment.getProperty("spring.jpa.hibernate.ddl-auto");

    // then
    assertThat(ddlAuto).isEqualTo("validate");
    assertThat(flyway.info().applied()).isNotEmpty();
    assertThat(flyway.info().pending()).isEmpty();
  }
}
