package dev.roomlock.booking.api;

import static dev.roomlock.booking.support.TestData.aHotel;
import static dev.roomlock.booking.support.TestData.aRoom;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.roomlock.booking.support.BookingIntegrationTest;
import dev.roomlock.booking.support.DatabaseCleaner;
import dev.roomlock.booking.support.TestData.HotelDraft;
import dev.roomlock.booking.support.TestData.RoomDraft;
import dev.roomlock.booking.support.TestJwts;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Catalog through HTTP and the real database (task 1.4, end-to-end scenario). Writing it is an
 * operator action and carries the {@code catalog:write} scope; reading it is open, so the two GETs
 * below deliberately send no token at all.
 */
@BookingIntegrationTest
class CatalogApiIntegrationTest {

  @Autowired private TestRestTemplate rest;
  @Autowired private ObjectMapper json;
  @Autowired private DatabaseCleaner databaseCleaner;

  @BeforeEach
  void setUp() {
    databaseCleaner.truncateAll();
  }

  @Test
  void shouldReadRoomBackWhenHotelAndRoomAreCreatedOverHttp() throws Exception {
    // given
    UUID hotelId = createHotel(aHotel());
    RoomDraft draft = aRoom().withNumber("101").withPricePerNight(new BigDecimal("150.00"));
    ResponseEntity<String> created = postRoom(hotelId, draft);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    UUID roomId = UUID.fromString(json.readTree(created.getBody()).path("id").asText());

    // when
    ResponseEntity<String> response = rest.getForEntity("/api/v1/rooms/{id}", String.class, roomId);

    // then
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode room = json.readTree(response.getBody());
    assertThat(room.path("id").asText()).isEqualTo(roomId.toString());
    assertThat(room.path("hotelId").asText()).isEqualTo(hotelId.toString());
    assertThat(room.path("number").asText()).isEqualTo("101");
    assertThat(room.path("capacity").asInt()).isEqualTo(draft.capacity());
    assertThat(response.getBody()).contains("\"pricePerNight\":150.00");
  }

  @Test
  void shouldReturnConflictWhenRoomNumberRepeatsWithinHotel() throws Exception {
    // given
    UUID hotelId = createHotel(aHotel());
    RoomDraft draft = aRoom().withNumber("101");
    assertThat(postRoom(hotelId, draft).getStatusCode()).isEqualTo(HttpStatus.CREATED);

    // when
    ResponseEntity<String> response = postRoom(hotelId, draft);

    // then
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(response.getHeaders().getContentType())
        .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    JsonNode problem = json.readTree(response.getBody());
    assertThat(problem.path("type").asText()).isEqualTo("urn:roomlock:problem:room-number-taken");
    assertThat(problem.path("hotelId").asText()).isEqualTo(hotelId.toString());
    assertThat(problem.path("number").asText()).isEqualTo("101");
  }

  @Test
  void shouldCreateRoomWhenSameNumberExistsInAnotherHotel() throws Exception {
    // given
    UUID hotelId = createHotel(aHotel());
    UUID otherHotelId = createHotel(aHotel().withName("Hotel Bristol"));
    RoomDraft draft = aRoom().withNumber("101");
    assertThat(postRoom(otherHotelId, draft).getStatusCode()).isEqualTo(HttpStatus.CREATED);

    // when
    ResponseEntity<String> response = postRoom(hotelId, draft);

    // then
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
  }

  @Test
  void shouldServeSwaggerUiWhenRequested() {
    // given
    String swaggerUi = "/swagger-ui.html";

    // when
    ResponseEntity<String> response = rest.getForEntity(swaggerUi, String.class);

    // then
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).contains("swagger-ui");
  }

  private UUID createHotel(HotelDraft hotel) throws Exception {
    Map<String, Object> body = Map.of("name", hotel.name(), "city", hotel.city());
    ResponseEntity<String> response =
        rest.exchange("/api/v1/hotels", HttpMethod.POST, operatorRequest(body), String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return UUID.fromString(json.readTree(response.getBody()).path("id").asText());
  }

  private ResponseEntity<String> postRoom(UUID hotelId, RoomDraft room) {
    Map<String, Object> body =
        Map.of(
            "number", room.number(),
            "capacity", room.capacity(),
            "pricePerNight", room.pricePerNight());
    return rest.exchange(
        "/api/v1/hotels/{id}/rooms", HttpMethod.POST, operatorRequest(body), String.class, hotelId);
  }

  private static HttpEntity<Object> operatorRequest(Object body) {
    return new HttpEntity<>(body, TestJwts.jsonBearer(TestJwts.operator()));
  }
}
