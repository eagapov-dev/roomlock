package dev.roomlock.booking.api;

import static dev.roomlock.booking.support.TestData.aHold;
import static dev.roomlock.booking.support.TestData.aHotel;
import static dev.roomlock.booking.support.TestData.aRoom;
import static dev.roomlock.booking.support.TestData.november;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.roomlock.booking.support.BookingIntegrationTest;
import dev.roomlock.booking.support.DatabaseCleaner;
import dev.roomlock.booking.support.TestData.HoldDraft;
import dev.roomlock.booking.support.TestData.RoomDraft;
import dev.roomlock.booking.support.TestJwts;
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

/**
 * Holding a room through HTTP and the real database (task 2.3, end-to-end scenario). Every request
 * under {@code /api/v1/bookings} carries the access token of its guest; the room is created with an
 * operator token, because writing the catalog is not a guest action.
 */
@BookingIntegrationTest
class BookingApiIntegrationTest {

  private static final String BOOKINGS = "/api/v1/bookings";
  private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

  @Autowired private TestRestTemplate rest;
  @Autowired private ObjectMapper json;
  @Autowired private DatabaseCleaner databaseCleaner;

  private UUID roomId;

  @BeforeEach
  void setUp() throws Exception {
    databaseCleaner.truncateAll();
    roomId = createRoom();
  }

  @Test
  void shouldReadHeldBookingBackWhenRoomIsHeldOverHttp() throws Exception {
    // given
    HoldDraft draft = aHold().withRoomId(roomId).withStay(november(10), november(13));

    // when
    ResponseEntity<String> created = postHold(draft);

    // then
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    UUID bookingId = UUID.fromString(json.readTree(created.getBody()).path("id").asText());
    ResponseEntity<String> found = getBooking(bookingId, draft.guestId());
    assertThat(found.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode booking = json.readTree(found.getBody());
    assertThat(booking.path("roomId").asText()).isEqualTo(roomId.toString());
    assertThat(booking.path("guestId").asText()).isEqualTo(draft.guestId().toString());
    assertThat(booking.path("checkIn").asText()).isEqualTo("2026-11-10");
    assertThat(booking.path("checkOut").asText()).isEqualTo("2026-11-13");
    assertThat(booking.path("status").asText()).isEqualTo("HELD");
    assertThat(found.getBody()).contains("\"totalPrice\":450.00");
  }

  @Test
  void shouldReturnTheSameBookingWhenTheSameKeyIsSentTwice() throws Exception {
    // given
    HoldDraft draft = aHold().withRoomId(roomId);
    ResponseEntity<String> created = postHold(draft);
    UUID bookingId = UUID.fromString(json.readTree(created.getBody()).path("id").asText());

    // when
    ResponseEntity<String> retry = postHold(draft);

    // then
    assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(json.readTree(retry.getBody()).path("id").asText()).isEqualTo(bookingId.toString());
  }

  @Test
  void shouldReturnConflictWhenAnotherGuestAsksForOverlappingStay() throws Exception {
    // given
    assertThat(
            postHold(aHold().withRoomId(roomId).withStay(november(10), november(13)))
                .getStatusCode())
        .isEqualTo(HttpStatus.CREATED);

    // when
    ResponseEntity<String> response =
        postHold(aHold().withRoomId(roomId).withStay(november(12), november(14)));

    // then
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(response.getHeaders().getContentType())
        .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    JsonNode problem = json.readTree(response.getBody());
    assertThat(problem.path("type").asText()).isEqualTo("urn:roomlock:problem:room-busy");
    assertThat(problem.path("roomId").asText()).isEqualTo(roomId.toString());
  }

  @Test
  void shouldReturnNotFoundWhenRoomOfTheHoldDoesNotExist() throws Exception {
    // given
    HoldDraft draft = aHold().withRoomId(UUID.randomUUID());

    // when
    ResponseEntity<String> response = postHold(draft);

    // then
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    JsonNode problem = json.readTree(response.getBody());
    assertThat(problem.path("type").asText()).isEqualTo("urn:roomlock:problem:not-found");
    assertThat(problem.path("resource").asText()).isEqualTo("room");
  }

  private ResponseEntity<String> postHold(HoldDraft draft) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("roomId", draft.roomId());
    body.put("checkIn", draft.checkIn().toString());
    body.put("checkOut", draft.checkOut().toString());
    HttpHeaders headers = TestJwts.jsonBearer(TestJwts.guest(draft.guestId()));
    headers.set(IDEMPOTENCY_KEY, draft.idempotencyKey());
    return rest.exchange(BOOKINGS, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
  }

  private ResponseEntity<String> getBooking(UUID bookingId, UUID guestId) {
    HttpEntity<Void> request = new HttpEntity<>(TestJwts.bearer(TestJwts.guest(guestId)));
    return rest.exchange(BOOKINGS + "/{id}", HttpMethod.GET, request, String.class, bookingId);
  }

  private UUID createRoom() throws Exception {
    ResponseEntity<String> hotel =
        rest.exchange(
            "/api/v1/hotels",
            HttpMethod.POST,
            operatorRequest(Map.of("name", aHotel().name(), "city", aHotel().city())),
            String.class);
    UUID hotelId = UUID.fromString(json.readTree(hotel.getBody()).path("id").asText());
    RoomDraft room = aRoom();
    ResponseEntity<String> created =
        rest.exchange(
            "/api/v1/hotels/{id}/rooms",
            HttpMethod.POST,
            operatorRequest(
                Map.of(
                    "number", room.number(),
                    "capacity", room.capacity(),
                    "pricePerNight", room.pricePerNight())),
            String.class,
            hotelId);
    return UUID.fromString(json.readTree(created.getBody()).path("id").asText());
  }

  private static HttpEntity<Object> operatorRequest(Object body) {
    return new HttpEntity<>(body, TestJwts.jsonBearer(TestJwts.operator()));
  }
}
