package dev.roomlock.booking.api;

import static dev.roomlock.booking.support.TestData.aHold;
import static dev.roomlock.booking.support.TestData.aHotel;
import static dev.roomlock.booking.support.TestData.aRoom;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * The filter chain of {@link ApiSecurityConfig} against the running application and the real {@code
 * NimbusJwtDecoder}: who is let in, who is refused, and what a refusal looks like.
 *
 * <p>The tokens are signed by the key pair of {@link TestJwts}, so an expired or foreign-signed
 * token is refused here for exactly the reason it would be refused in production - no identity
 * provider and no container take part.
 */
@BookingIntegrationTest
class ApiSecurityIntegrationTest {

  private static final String HOTELS = "/api/v1/hotels";
  private static final String BOOKINGS = "/api/v1/bookings";
  private static final String HEALTH = "/actuator/health";
  private static final String PROMETHEUS = "/actuator/prometheus";
  private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

  @Autowired private TestRestTemplate rest;
  @Autowired private ObjectMapper json;
  @Autowired private DatabaseCleaner databaseCleaner;

  @BeforeEach
  void setUp() {
    databaseCleaner.truncateAll();
  }

  @Test
  void shouldRefuseHoldWhenRequestIsAnonymous() throws Exception {
    // given
    HoldDraft draft = aHold();
    Map<String, Object> body = holdBody(draft);
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set(IDEMPOTENCY_KEY, draft.idempotencyKey());

    // when
    ResponseEntity<String> response =
        rest.exchange(BOOKINGS, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);

    // then
    assertUnauthenticated(response);
  }

  @ParameterizedTest
  @ValueSource(strings = {"/pay", "/cancel"})
  void shouldRefuseBookingActionWhenRequestIsAnonymous(String action) throws Exception {
    // given
    String path = BOOKINGS + "/" + UUID.randomUUID() + action;

    // when
    ResponseEntity<String> response = rest.postForEntity(path, null, String.class);

    // then
    assertUnauthenticated(response);
  }

  @Test
  void shouldRefuseBookingReadWhenRequestIsAnonymous() throws Exception {
    // given
    UUID bookingId = UUID.randomUUID();

    // when
    ResponseEntity<String> response =
        rest.getForEntity(BOOKINGS + "/{id}", String.class, bookingId);

    // then
    assertUnauthenticated(response);
  }

  @Test
  void shouldRefuseHotelCreationWhenTokenHasNoCatalogScope() throws Exception {
    // given
    String guestToken = TestJwts.guest();

    // when
    ResponseEntity<String> response = createHotel(guestToken);

    // then
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertProblem(response, "forbidden");
  }

  @Test
  void shouldRefuseRoomCreationWhenTokenHasNoCatalogScope() throws Exception {
    // given
    UUID hotelId =
        UUID.fromString(
            json.readTree(createHotel(TestJwts.operator()).getBody()).path("id").asText());
    RoomDraft room = aRoom();

    // when
    ResponseEntity<String> response = addRoom(hotelId, room, TestJwts.guest());

    // then
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertProblem(response, "forbidden");
  }

  @Test
  void shouldCreateHotelWhenTokenCarriesTheCatalogScope() throws Exception {
    // given
    String operatorToken = TestJwts.operator();

    // when
    ResponseEntity<String> response = createHotel(operatorToken);

    // then
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
  }

  /** A guest has to see a room and its price before signing in, so reading the catalog is open. */
  @Test
  void shouldReadHotelAndRoomWhenRequestIsAnonymous() throws Exception {
    // given
    UUID hotelId =
        UUID.fromString(
            json.readTree(createHotel(TestJwts.operator()).getBody()).path("id").asText());
    UUID roomId =
        UUID.fromString(
            json.readTree(addRoom(hotelId, aRoom(), TestJwts.operator()).getBody())
                .path("id")
                .asText());

    // when
    ResponseEntity<String> hotel = rest.getForEntity(HOTELS + "/{id}", String.class, hotelId);
    ResponseEntity<String> room = rest.getForEntity("/api/v1/rooms/{id}", String.class, roomId);

    // then
    assertThat(hotel.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(room.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  /**
   * The Cloud Run probes and the compose healthcheck fetch health, and all three metric scrapers
   * scrape the metrics with no credential configured anywhere: both endpoints stay open.
   */
  @ParameterizedTest
  @ValueSource(strings = {HEALTH, PROMETHEUS})
  void shouldServeActuatorEndpointWhenRequestIsAnonymous(String path) {
    // given
    // when
    ResponseEntity<String> response = rest.getForEntity(path, String.class);

    // then
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  /**
   * The Cloud Run startup and liveness probes read the groups of health, not health itself ({@code
   * management.endpoint.health.probes.enabled} under the {@code cloud} profile), so the filter
   * chain has to let those paths through as well. 404 rather than 403 is the whole assertion: the
   * request reached the actuator, which under this profile has no such group to serve. A 403 would
   * mean a revision whose probes never pass.
   */
  @ParameterizedTest
  @ValueSource(strings = {"/actuator/health/liveness", "/actuator/health/readiness"})
  void shouldNotBlockTheProbeGroupsOfHealthWhenRequestIsAnonymous(String path) {
    // given
    // when
    ResponseEntity<String> response = rest.getForEntity(path, String.class);

    // then
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  /** Nothing outside the documented contract is reachable, with or without a token. */
  @Test
  void shouldRefuseAnUndocumentedActuatorEndpointWhenTokenIsValid() throws Exception {
    // given
    HttpEntity<Void> request = new HttpEntity<>(TestJwts.bearer(TestJwts.operator()));

    // when
    ResponseEntity<String> response =
        rest.exchange("/actuator/env", HttpMethod.GET, request, String.class);

    // then
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void shouldRefuseBookingReadWhenTokenHasExpired() throws Exception {
    // given
    String expired = TestJwts.expired(UUID.randomUUID());

    // when
    ResponseEntity<String> response = getBookingWith(expired);

    // then
    assertUnauthenticated(response);
  }

  @Test
  void shouldRefuseBookingReadWhenTokenIsSignedByAnotherKey() throws Exception {
    // given
    String foreign = TestJwts.wronglySigned(UUID.randomUUID());

    // when
    ResponseEntity<String> response = getBookingWith(foreign);

    // then
    assertUnauthenticated(response);
  }

  @Test
  void shouldRefuseBookingReadWhenTokenIsNotAJwtAtAll() throws Exception {
    // given
    String garbage = "not.a.token";

    // when
    ResponseEntity<String> response = getBookingWith(garbage);

    // then
    assertUnauthenticated(response);
  }

  private ResponseEntity<String> getBookingWith(String token) {
    HttpEntity<Void> request = new HttpEntity<>(TestJwts.bearer(token));
    return rest.exchange(
        BOOKINGS + "/{id}", HttpMethod.GET, request, String.class, UUID.randomUUID());
  }

  private ResponseEntity<String> createHotel(String token) {
    Map<String, Object> body = Map.of("name", aHotel().name(), "city", aHotel().city());
    return rest.exchange(
        HOTELS, HttpMethod.POST, new HttpEntity<>(body, TestJwts.jsonBearer(token)), String.class);
  }

  private ResponseEntity<String> addRoom(UUID hotelId, RoomDraft room, String token) {
    Map<String, Object> body =
        Map.of(
            "number", room.number(),
            "capacity", room.capacity(),
            "pricePerNight", room.pricePerNight());
    return rest.exchange(
        HOTELS + "/{id}/rooms",
        HttpMethod.POST,
        new HttpEntity<>(body, TestJwts.jsonBearer(token)),
        String.class,
        hotelId);
  }

  private static Map<String, Object> holdBody(HoldDraft draft) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("roomId", draft.roomId());
    body.put("checkIn", draft.checkIn().toString());
    body.put("checkOut", draft.checkOut().toString());
    return body;
  }

  /**
   * 401, the {@code WWW-Authenticate} of RFC 6750, and the problem shape of every other failure.
   */
  private void assertUnauthenticated(ResponseEntity<String> response) throws Exception {
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
    assertProblem(response, "unauthenticated");
  }

  private void assertProblem(ResponseEntity<String> response, String type) throws Exception {
    assertThat(response.getHeaders().getContentType())
        .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    JsonNode problem = json.readTree(response.getBody());
    assertThat(problem.path("type").asText()).isEqualTo("urn:roomlock:problem:" + type);
  }
}
