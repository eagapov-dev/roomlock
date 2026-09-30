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
import dev.roomlock.booking.support.StubPaymentGateway;
import dev.roomlock.booking.support.TestData.BookingDraft;
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
 * Paying for a booking through HTTP and the real database (end-to-end scenario). Every pay carries
 * the access token of the guest whose booking it is, because a pay of somebody else's booking is
 * not a request this API answers at all.
 */
@BookingIntegrationTest
class BookingPayApiIntegrationTest {

  private static final String BOOKINGS = "/api/v1/bookings";
  private static final String PAY = BOOKINGS + "/{id}/pay";
  private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

  @Autowired private TestRestTemplate rest;
  @Autowired private ObjectMapper json;
  @Autowired private StubPaymentGateway provider;
  @Autowired private BookingTable bookingTable;
  @Autowired private DatabaseCleaner databaseCleaner;

  private UUID roomId;

  @BeforeEach
  void setUp() throws Exception {
    databaseCleaner.truncateAll();
    provider.reset();
    roomId = createRoom();
  }

  @Test
  void shouldReportConfirmedBookingWhenGuestPaysOverHttp() throws Exception {
    // given
    HoldDraft draft = aHold().withRoomId(roomId).withStay(november(10), november(13));
    UUID bookingId = hold(draft);

    // when
    ResponseEntity<String> response = pay(bookingId, draft.guestId());

    // then
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode booking = json.readTree(response.getBody());
    assertThat(booking.path("id").asText()).isEqualTo(bookingId.toString());
    assertThat(booking.path("status").asText()).isEqualTo("CONFIRMED");
    assertThat(booking.path("cancelReason").isNull()).isTrue();
    JsonNode found = json.readTree(getBooking(bookingId, draft.guestId()).getBody());
    assertThat(found.path("status").asText()).isEqualTo("CONFIRMED");
  }

  @Test
  void shouldChargeOnceAndAnswerOkAgainWhenPaymentIsRetried() throws Exception {
    // given
    HoldDraft draft = aHold().withRoomId(roomId);
    UUID bookingId = hold(draft);
    assertThat(pay(bookingId, draft.guestId()).getStatusCode()).isEqualTo(HttpStatus.OK);

    // when
    ResponseEntity<String> retry = pay(bookingId, draft.guestId());

    // then
    assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(json.readTree(retry.getBody()).path("status").asText()).isEqualTo("CONFIRMED");
    assertThat(provider.received()).hasSize(1);
  }

  @Test
  void shouldReturnConflictWithTheReasonWhenChargeIsDeclined() throws Exception {
    // given
    HoldDraft draft = aHold().withRoomId(roomId);
    UUID bookingId = hold(draft);
    provider.declineEverything("AMOUNT_ABOVE_LIMIT");

    // when
    ResponseEntity<String> response = pay(bookingId, draft.guestId());

    // then
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(response.getHeaders().getContentType())
        .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    JsonNode problem = json.readTree(response.getBody());
    assertThat(problem.path("type").asText()).isEqualTo("urn:roomlock:problem:payment-declined");
    assertThat(problem.path("bookingId").asText()).isEqualTo(bookingId.toString());
    assertThat(problem.path("reason").asText()).isEqualTo("AMOUNT_ABOVE_LIMIT");
    assertThat(bookingTable.status(bookingId)).isEqualTo("CANCELLED");
    assertThat(bookingTable.cancelReason(bookingId)).isEqualTo("PAYMENT_DECLINED");
  }

  @Test
  void shouldReturnConflictWhenBookingIsCancelled() throws Exception {
    // given
    BookingDraft draft =
        aBooking()
            .withRoomId(roomId)
            .withStay(november(10), november(13))
            .withStatus(BookingStatus.CANCELLED);
    UUID bookingId = bookingTable.insert(draft);

    // when
    ResponseEntity<String> response = pay(bookingId, draft.guestId());

    // then
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    JsonNode problem = json.readTree(response.getBody());
    assertThat(problem.path("type").asText()).isEqualTo("urn:roomlock:problem:booking-not-payable");
    assertThat(problem.path("status").asText()).isEqualTo("CANCELLED");
    assertThat(provider.received()).isEmpty();
  }

  @Test
  void shouldReturnServiceUnavailableAndKeepBookingPayingWhenProviderGivesNoAnswer()
      throws Exception {
    // given
    HoldDraft draft = aHold().withRoomId(roomId);
    UUID bookingId = hold(draft);
    provider.beUnavailable();

    // when
    ResponseEntity<String> response = pay(bookingId, draft.guestId());

    // then
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    JsonNode problem = json.readTree(response.getBody());
    assertThat(problem.path("type").asText()).isEqualTo("urn:roomlock:problem:payment-unavailable");
    assertThat(problem.path("bookingId").asText()).isEqualTo(bookingId.toString());
    assertThat(bookingTable.status(bookingId)).isEqualTo("PAYING");
    assertThat(bookingTable.paymentStartedAt(bookingId)).isPresent();
  }

  @Test
  void shouldReturnNotFoundWhenBookingOfThePaymentDoesNotExist() throws Exception {
    // given
    UUID unknownId = UUID.randomUUID();

    // when
    ResponseEntity<String> response = pay(unknownId, UUID.randomUUID());

    // then
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    JsonNode problem = json.readTree(response.getBody());
    assertThat(problem.path("type").asText()).isEqualTo("urn:roomlock:problem:not-found");
    assertThat(problem.path("resource").asText()).isEqualTo("booking");
  }

  private ResponseEntity<String> pay(UUID bookingId, UUID guestId) {
    return rest.exchange(PAY, HttpMethod.POST, guestRequest(guestId), String.class, bookingId);
  }

  private ResponseEntity<String> getBooking(UUID bookingId, UUID guestId) {
    return rest.exchange(
        BOOKINGS + "/{id}", HttpMethod.GET, guestRequest(guestId), String.class, bookingId);
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
