package dev.roomlock.booking.outbox;

import static dev.roomlock.booking.support.TestData.HOLD_TTL;
import static dev.roomlock.booking.support.TestData.NOW;
import static dev.roomlock.booking.support.TestData.aHold;
import static dev.roomlock.booking.support.TestData.aHotel;
import static dev.roomlock.booking.support.TestData.aRoom;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.roomlock.booking.domain.Booking;
import dev.roomlock.booking.hold.HoldResult;
import dev.roomlock.booking.hold.HoldService;
import dev.roomlock.booking.infra.HotelRepository;
import dev.roomlock.booking.infra.RoomRepository;
import dev.roomlock.booking.lifecycle.LifecycleService;
import dev.roomlock.booking.support.BookingIntegrationTest;
import dev.roomlock.booking.support.DatabaseCleaner;
import dev.roomlock.booking.support.MutableClock;
import dev.roomlock.booking.support.OutboxTable;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Every way a booking loses its room writes the cancellation event with the reason (task 4.1). */
@BookingIntegrationTest
class CancellationOutboxTest {

  private static final String BOOKING_HELD = "BookingHeld";
  private static final String BOOKING_CANCELLED = "BookingCancelled";

  @Autowired private HoldService holds;
  @Autowired private LifecycleService lifecycle;
  @Autowired private OutboxTable outbox;
  @Autowired private ObjectMapper json;
  @Autowired private MutableClock clock;
  @Autowired private DatabaseCleaner databaseCleaner;
  @Autowired private HotelRepository hotels;
  @Autowired private RoomRepository rooms;

  private UUID roomId;

  @BeforeEach
  void setUp() {
    databaseCleaner.truncateAll();
    clock.setInstant(NOW);
    UUID hotelId = hotels.save(aHotel().build()).getId();
    roomId = rooms.save(aRoom().withHotelId(hotelId).build()).getId();
  }

  @Test
  void shouldWriteCancelledEventWithUserReasonWhenGuestCancels() throws Exception {
    // given
    Booking booking = held();

    // when
    lifecycle.cancel(booking.getId());

    // then
    assertThat(outbox.typesOf(booking.getId())).containsExactly(BOOKING_HELD, BOOKING_CANCELLED);
    JsonNode event = json.readTree(cancellationPayloadOf(booking.getId()));
    assertThat(event.path("bookingId").asText()).isEqualTo(booking.getId().toString());
    assertThat(event.path("roomId").asText()).isEqualTo(roomId.toString());
    assertThat(event.path("guestId").asText()).isEqualTo(booking.getGuestId().toString());
    assertThat(event.path("reason").asText()).isEqualTo("USER_CANCELLED");
  }

  @Test
  void shouldWriteCancelledEventWithExpiryReasonWhenHoldExpires() throws Exception {
    // given
    Booking booking = held();
    clock.advance(HOLD_TTL.plus(Duration.ofMinutes(1)));

    // when
    int expired = lifecycle.expireHolds();

    // then
    assertThat(expired).isEqualTo(1);
    assertThat(outbox.typesOf(booking.getId())).containsExactly(BOOKING_HELD, BOOKING_CANCELLED);
    JsonNode event = json.readTree(cancellationPayloadOf(booking.getId()));
    assertThat(event.path("reason").asText()).isEqualTo("HOLD_EXPIRED");
    assertThat(event.path("occurredAt").asText()).isEqualTo(clock.instant().toString());
  }

  @Test
  void shouldWriteOneCancelledEventWhenBookingIsCancelledTwice() {
    // given
    Booking booking = held();
    lifecycle.cancel(booking.getId());

    // when
    lifecycle.cancel(booking.getId());

    // then
    assertThat(outbox.typesOf(booking.getId())).containsExactly(BOOKING_HELD, BOOKING_CANCELLED);
  }

  @Test
  void shouldWriteOneEventPerExpiredHoldWhenSeveralExpireTogether() {
    // given
    Booking first = held();
    Booking second = heldInAnotherRoom();
    clock.advance(HOLD_TTL.plus(Duration.ofMinutes(1)));

    // when
    int expired = lifecycle.expireHolds();

    // then
    assertThat(expired).isEqualTo(2);
    assertThat(outbox.typesOf(first.getId())).containsExactly(BOOKING_HELD, BOOKING_CANCELLED);
    assertThat(outbox.typesOf(second.getId())).containsExactly(BOOKING_HELD, BOOKING_CANCELLED);
  }

  private Booking held() {
    return ((HoldResult.Held) holds.hold(aHold().withRoomId(roomId).build())).booking();
  }

  private Booking heldInAnotherRoom() {
    UUID hotelId = hotels.save(aHotel().withName("Hotel Bristol").build()).getId();
    UUID otherRoomId = rooms.save(aRoom().withHotelId(hotelId).build()).getId();
    return ((HoldResult.Held) holds.hold(aHold().withRoomId(otherRoomId).build())).booking();
  }

  /** The payload of the cancellation, which is the last row of the booking. */
  private String cancellationPayloadOf(UUID bookingId) {
    return outbox.payloadOfLast(bookingId, BOOKING_CANCELLED);
  }
}
