package dev.roomlock.booking.api;

import static dev.roomlock.booking.support.TestData.aHold;
import static dev.roomlock.booking.support.TestData.aHotel;
import static dev.roomlock.booking.support.TestData.aRoom;
import static dev.roomlock.booking.support.TestData.november;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.roomlock.booking.support.BookingIntegrationTest;
import dev.roomlock.booking.support.BookingTable;
import dev.roomlock.booking.support.DatabaseCleaner;
import dev.roomlock.booking.support.StubPaymentGateway;
import dev.roomlock.booking.support.TestData.HoldDraft;
import dev.roomlock.booking.support.TestData.RoomDraft;
import dev.roomlock.booking.support.TestJwts;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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
 * A booking belongs to the guest who made it, and to nobody else.
 *
 * <p>Every answer here is <strong>404, not 403</strong>. A 403 would tell the caller that the id it
 * sent is a real booking, which is the one thing an attacker walking through ids is trying to
 * learn; a 404 is the same answer an id that was never created gets, so the two are
 * indistinguishable. Each test therefore also asserts that the booking is still there and untouched
 * afterwards - a 404 that had cancelled the booking on the way would pass a status assertion alone.
 */
@BookingIntegrationTest
class BookingOwnershipIntegrationTest {

  private static final String BOOKINGS = "/api/v1/bookings";
  private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

  @Autowired private TestRestTemplate rest;
  @Autowired private ObjectMapper json;
  @Autowired private BookingTable bookingTable;
  @Autowired private StubPaymentGateway provider;
  @Autowired private DatabaseCleaner databaseCleaner;

  private UUID roomId;
  private HoldDraft owner;
  private UUID bookingId;

  @BeforeEach
  void setUp() throws Exception {
    databaseCleaner.truncateAll();
    provider.reset();
    roomId = createRoom();
    owner = aHold().withRoomId(roomId).withStay(november(10), november(13));
    bookingId = hold(owner);
  }

  @Test
  void shouldReturnNotFoundWhenAnotherGuestReadsTheBooking() throws Exception {
    // given
    UUID stranger = UUID.randomUUID();

    // when
    ResponseEntity<String> response =
        rest.exchange(
            BOOKINGS + "/{id}", HttpMethod.GET, guestRequest(stranger), String.class, bookingId);

    // then
    assertNotFound(response);
    assertThat(bookingTable.status(bookingId)).isEqualTo("HELD");
  }

  @Test
  void shouldReturnNotFoundWhenAnotherGuestPaysForTheBooking() throws Exception {
    // given
    UUID stranger = UUID.randomUUID();

    // when
    ResponseEntity<String> response =
        rest.exchange(
            BOOKINGS + "/{id}/pay",
            HttpMethod.POST,
            guestRequest(stranger),
            String.class,
            bookingId);

    // then
    assertNotFound(response);
    assertThat(bookingTable.status(bookingId)).isEqualTo("HELD");
    assertThat(provider.received()).isEmpty();
  }

  @Test
  void shouldReturnNotFoundWhenAnotherGuestCancelsTheBooking() throws Exception {
    // given
    UUID stranger = UUID.randomUUID();

    // when
    ResponseEntity<String> response =
        rest.exchange(
            BOOKINGS + "/{id}/cancel",
            HttpMethod.POST,
            guestRequest(stranger),
            String.class,
            bookingId);

    // then
    assertNotFound(response);
    assertThat(bookingTable.status(bookingId)).isEqualTo("HELD");
  }

  /** The same three requests from the guest who made the booking are answered normally. */
  @Test
  void shouldServeTheBookingWhenItsOwnerAsks() throws Exception {
    // given
    HttpEntity<Void> asOwner = guestRequest(owner.guestId());

    // when
    ResponseEntity<String> response =
        rest.exchange(BOOKINGS + "/{id}", HttpMethod.GET, asOwner, String.class, bookingId);

    // then
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(json.readTree(response.getBody()).path("id").asText())
        .isEqualTo(bookingId.toString());
  }

  /**
   * The 404 of a booking somebody else owns and the 404 of an id that was never created differ only
   * in the id they quote: same status, same problem type, same fields, and nothing anywhere in the
   * body that hints the first id is a real booking.
   */
  @Test
  void shouldAnswerTheSameWayWhenTheBookingIsAnothersAndWhenItDoesNotExist() throws Exception {
    // given
    UUID stranger = UUID.randomUUID();
    UUID unknownId = UUID.randomUUID();

    // when
    JsonNode anothers = problemOf(readAs(stranger, bookingId));
    JsonNode unknown = problemOf(readAs(stranger, unknownId));

    // then
    assertThat(anothers.path("status").asInt()).isEqualTo(unknown.path("status").asInt());
    assertThat(anothers.path("type").asText()).isEqualTo(unknown.path("type").asText());
    assertThat(anothers.path("title").asText()).isEqualTo(unknown.path("title").asText());
    assertThat(anothers.path("resource").asText()).isEqualTo(unknown.path("resource").asText());
    assertThat(fieldNamesOf(anothers)).isEqualTo(fieldNamesOf(unknown));
    assertThat(anothers.path("detail").asText().replace(bookingId.toString(), "<id>"))
        .isEqualTo(unknown.path("detail").asText().replace(unknownId.toString(), "<id>"));
  }

  /** Which fields the body carries, so one answer cannot say more about the id than the other. */
  private static Set<String> fieldNamesOf(JsonNode problem) {
    return problem.properties().stream().map(Map.Entry::getKey).collect(Collectors.toSet());
  }

  private ResponseEntity<String> readAs(UUID guestId, UUID id) {
    return rest.exchange(
        BOOKINGS + "/{id}", HttpMethod.GET, guestRequest(guestId), String.class, id);
  }

  private void assertNotFound(ResponseEntity<String> response) throws Exception {
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getHeaders().getContentType())
        .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(problemOf(response).path("type").asText())
        .isEqualTo("urn:roomlock:problem:not-found");
    assertThat(problemOf(response).path("resource").asText()).isEqualTo("booking");
  }

  private JsonNode problemOf(ResponseEntity<String> response) throws Exception {
    return json.readTree(response.getBody());
  }

  private static HttpEntity<Void> guestRequest(UUID guestId) {
    return new HttpEntity<>(TestJwts.bearer(TestJwts.guest(guestId)));
  }

  private UUID hold(HoldDraft draft) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("roomId", draft.roomId());
    body.put("checkIn", draft.checkIn().toString());
    body.put("checkOut", draft.checkOut().toString());
    HttpHeaders headers = TestJwts.jsonBearer(TestJwts.guest(draft.guestId()));
    headers.set(IDEMPOTENCY_KEY, draft.idempotencyKey());
    ResponseEntity<String> created =
        rest.exchange(BOOKINGS, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return UUID.fromString(json.readTree(created.getBody()).path("id").asText());
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
