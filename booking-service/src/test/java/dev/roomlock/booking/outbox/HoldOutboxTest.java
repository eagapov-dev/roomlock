package dev.roomlock.booking.outbox;

import static dev.roomlock.booking.support.TestData.NOW;
import static dev.roomlock.booking.support.TestData.aHold;
import static dev.roomlock.booking.support.TestData.aHotel;
import static dev.roomlock.booking.support.TestData.aRoom;
import static dev.roomlock.booking.support.TestData.november;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.roomlock.booking.domain.Booking;
import dev.roomlock.booking.hold.HoldResult;
import dev.roomlock.booking.hold.HoldService;
import dev.roomlock.booking.infra.HotelRepository;
import dev.roomlock.booking.infra.RoomRepository;
import dev.roomlock.booking.support.BookingIntegrationTest;
import dev.roomlock.booking.support.DatabaseCleaner;
import dev.roomlock.booking.support.OutboxTable;
import dev.roomlock.booking.support.TestData.HoldDraft;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A hold and its event are one transaction (task 4.1): a room that was held has an event waiting,
 * and a hold that changed nothing has none.
 */
@BookingIntegrationTest
class HoldOutboxTest {

  private static final String BOOKING_HELD = "BookingHeld";

  @Autowired private HoldService holds;
  @Autowired private OutboxTable outbox;
  @Autowired private ObjectMapper json;
  @Autowired private DatabaseCleaner databaseCleaner;
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
  void shouldWriteHeldEventWhenRoomIsHeld() throws Exception {
    // given
    HoldDraft draft = aHold().withRoomId(roomId).withStay(november(10), november(13));

    // when
    Booking booking = ((HoldResult.Held) holds.hold(draft.build())).booking();

    // then
    assertThat(outbox.typesOf(booking.getId())).containsExactly(BOOKING_HELD);
    JsonNode event = json.readTree(outbox.payloadOf(booking.getId()));
    assertThat(event.path("bookingId").asText()).isEqualTo(booking.getId().toString());
    assertThat(event.path("roomId").asText()).isEqualTo(roomId.toString());
    assertThat(event.path("guestId").asText()).isEqualTo(draft.guestId().toString());
    assertThat(event.path("checkIn").asText()).isEqualTo("2026-11-10");
    assertThat(event.path("checkOut").asText()).isEqualTo("2026-11-13");
    assertThat(event.path("totalPrice").asText()).isEqualTo("450.00");
    assertThat(event.path("currency").asText()).isEqualTo("PLN");
    assertThat(event.path("holdExpiresAt").asText())
        .isEqualTo(booking.getHoldExpiresAt().toString());
    assertThat(event.path("occurredAt").asText()).isEqualTo(NOW.toString());
    assertThat(event.path("eventId").asText()).isNotBlank();
  }

  @Test
  void shouldWriteNoEventWhenRoomIsBusy() {
    // given
    holds.hold(aHold().withRoomId(roomId).withStay(november(10), november(13)).build());
    int afterFirstHold = outbox.count();

    // when
    HoldResult result =
        holds.hold(aHold().withRoomId(roomId).withStay(november(12), november(14)).build());

    // then
    assertThat(result).isInstanceOf(HoldResult.RoomBusy.class);
    assertThat(outbox.count()).isEqualTo(afterFirstHold);
  }

  @Test
  void shouldWriteOneEventWhenRequestIsRetriedWithSameIdempotencyKey() {
    // given
    HoldDraft draft = aHold().withRoomId(roomId);
    Booking booking = ((HoldResult.Held) holds.hold(draft.build())).booking();

    // when
    holds.hold(draft.build());

    // then
    assertThat(outbox.typesOf(booking.getId())).containsExactly(BOOKING_HELD);
    assertThat(outbox.count()).isEqualTo(1);
  }

  @Test
  void shouldLeaveEventUnpublishedWhenItIsWritten() {
    // given
    Booking booking = ((HoldResult.Held) holds.hold(aHold().withRoomId(roomId).build())).booking();

    // when
    // then the publisher has not run: scheduling is off under the test profile
    assertThat(outbox.typesOf(booking.getId())).containsExactly(BOOKING_HELD);
    assertThat(outbox.count()).isEqualTo(1);
  }
}
