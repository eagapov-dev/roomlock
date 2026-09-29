package dev.roomlock.booking.api;

import static dev.roomlock.booking.support.TestData.aBooking;
import static dev.roomlock.booking.support.TestData.aHold;
import static dev.roomlock.booking.support.TestData.aHotel;
import static dev.roomlock.booking.support.TestData.aRoom;
import static dev.roomlock.booking.support.TestData.november;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.roomlock.booking.domain.BookingStatus;
import dev.roomlock.booking.support.BookingIntegrationTest;
import dev.roomlock.booking.support.BookingTable;
import dev.roomlock.booking.support.DatabaseCleaner;
import dev.roomlock.booking.support.OutboxTable;
import dev.roomlock.booking.support.TestData.HoldDraft;
import dev.roomlock.booking.support.TestData.RoomDraft;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Cancelling a booking through HTTP and the real database (end-to-end scenario). */
@BookingIntegrationTest
class BookingCancelApiIntegrationTest {

  private static final String BOOKINGS = "/api/v1/bookings";
  private static final String CANCEL = BOOKINGS + "/{id}/cancel";
  private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

  @Autowired private TestRestTemplate rest;
  @Autowired private ObjectMapper json;
  @Autowired private BookingTable bookingTable;
  @Autowired private OutboxTable outbox;
  @Autowired private DatabaseCleaner databaseCleaner;

  private UUID roomId;

  @BeforeEach
  void setUp() throws Exception {
    databaseCleaner.truncateAll();
    roomId = createRoom();
  }

  @Test
  void shouldReportCancelledBookingWhenGuestCancelsOverHttp() throws Exception {
    // given
    UUID bookingId = hold(aHold().withRoomId(roomId).withStay(november(10), november(13)));

    // when
    ResponseEntity<String> response = cancel(bookingId);

    // then
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode booking = json.readTree(response.getBody());
    assertThat(booking.path("id").asText()).isEqualTo(bookingId.toString());
    assertThat(booking.path("status").asText()).isEqualTo("CANCELLED");
    assertThat(booking.path("cancelReason").asText()).isEqualTo("USER_CANCELLED");
    JsonNode found =
        json.readTree(rest.getForEntity(BOOKINGS + "/{id}", String.class, bookingId).getBody());
    assertThat(found.path("status").asText()).isEqualTo("CANCELLED");
  }

  @Test
  void shouldReturnOkAgainWhenCancelIsRepeated() throws Exception {
    // given
    UUID bookingId = hold(aHold().withRoomId(roomId));
    assertThat(cancel(bookingId).getStatusCode()).isEqualTo(HttpStatus.OK);

    // when
    ResponseEntity<String> retry = cancel(bookingId);

    // then
    assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(json.readTree(retry.getBody()).path("status").asText()).isEqualTo("CANCELLED");
  }

  /** A paid stay may be dropped, and asks payment-service for the money back (rule K-1). */
  @Test
  void shouldReportCancelledBookingWhenConfirmedStayIsDropped() throws Exception {
    // given
    UUID bookingId =
        bookingTable.insert(
            aBooking()
                .withRoomId(roomId)
                .withStay(november(10), november(13))
                .withStatus(BookingStatus.CONFIRMED));

    // when
    ResponseEntity<String> response = cancel(bookingId);

    // then
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode booking = json.readTree(response.getBody());
    assertThat(booking.path("status").asText()).isEqualTo("CANCELLED");
    assertThat(booking.path("cancelReason").asText()).isEqualTo("USER_CANCELLED");
    assertThat(outbox.typesOf(bookingId)).contains("RefundRequested");
  }

  /** A charge is in flight: only the payment flow may decide how a PAYING booking ends. */
  @Test
  void shouldReturnConflictWhenBookingIsPaying() throws Exception {
    // given
    UUID bookingId =
        bookingTable.insert(
            aBooking()
                .withRoomId(roomId)
                .withStay(november(10), november(13))
                .withStatus(BookingStatus.PAYING));

    // when
    ResponseEntity<String> response = cancel(bookingId);

    // then
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(response.getHeaders().getContentType())
        .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    JsonNode problem = json.readTree(response.getBody());
    assertThat(problem.path("type").asText())
        .isEqualTo("urn:roomlock:problem:booking-not-cancellable");
    assertThat(problem.path("bookingId").asText()).isEqualTo(bookingId.toString());
    assertThat(problem.path("status").asText()).isEqualTo("PAYING");
    assertThat(bookingTable.status(bookingId)).isEqualTo("PAYING");
  }

  @Test
  void shouldReturnNotFoundWhenBookingOfTheCancelDoesNotExist() throws Exception {
    // given
    UUID unknownId = UUID.randomUUID();

    // when
    ResponseEntity<String> response = cancel(unknownId);

    // then
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    JsonNode problem = json.readTree(response.getBody());
    assertThat(problem.path("type").asText()).isEqualTo("urn:roomlock:problem:not-found");
    assertThat(problem.path("resource").asText()).isEqualTo("booking");
  }

  private ResponseEntity<String> cancel(UUID bookingId) {
    return rest.postForEntity(CANCEL, null, String.class, bookingId);
  }

  private UUID hold(HoldDraft draft) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("roomId", draft.roomId());
    body.put("guestId", draft.guestId());
    body.put("checkIn", draft.checkIn().toString());
    body.put("checkOut", draft.checkOut().toString());
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set(IDEMPOTENCY_KEY, draft.idempotencyKey());
    ResponseEntity<String> created =
        rest.exchange(BOOKINGS, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return UUID.fromString(json.readTree(created.getBody()).path("id").asText());
  }

  private UUID createRoom() throws Exception {
    ResponseEntity<String> hotel =
        rest.postForEntity(
            "/api/v1/hotels",
            Map.of("name", aHotel().name(), "city", aHotel().city()),
            String.class);
    UUID hotelId = UUID.fromString(json.readTree(hotel.getBody()).path("id").asText());
    RoomDraft room = aRoom();
    ResponseEntity<String> created =
        rest.postForEntity(
            "/api/v1/hotels/{id}/rooms",
            Map.of(
                "number", room.number(),
                "capacity", room.capacity(),
                "pricePerNight", room.pricePerNight()),
            String.class,
            hotelId);
    return UUID.fromString(json.readTree(created.getBody()).path("id").asText());
  }
}
