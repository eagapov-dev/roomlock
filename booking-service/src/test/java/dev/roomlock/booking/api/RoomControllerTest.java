package dev.roomlock.booking.api;

import static dev.roomlock.booking.support.TestData.aRoom;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.roomlock.booking.catalog.AddRoomCommand;
import dev.roomlock.booking.catalog.AddRoomResult;
import dev.roomlock.booking.catalog.CatalogService;
import dev.roomlock.booking.domain.ResourceNotFoundException;
import dev.roomlock.booking.domain.Room;
import dev.roomlock.booking.support.TestData.RoomDraft;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@WebMvcTest
@Import(Problems.class)
class RoomControllerTest {

  private static final String HOTEL_ROOMS = "/api/v1/hotels/{hotelId}/rooms";
  private static final String ROOM = "/api/v1/rooms/{id}";

  @Autowired private MockMvcTester mvc;
  @Autowired private ObjectMapper json;
  @MockitoBean private CatalogService catalog;

  @Test
  void shouldReturnCreatedWithRoomIdWhenRoomIsValid() throws Exception {
    // given
    UUID hotelId = UUID.randomUUID();
    RoomDraft draft = aRoom().withHotelId(hotelId);
    Room room = draft.build();
    given(catalog.addRoom(any())).willReturn(new AddRoomResult.Added(room));

    // when
    MvcTestResult result = postRoom(hotelId, roomBody(draft));

    // then
    assertThat(result).hasStatus(HttpStatus.CREATED);
    assertThat(result).bodyJson().extractingPath("$.id").isEqualTo(room.getId().toString());
    ArgumentCaptor<AddRoomCommand> command = ArgumentCaptor.forClass(AddRoomCommand.class);
    then(catalog).should().addRoom(command.capture());
    assertThat(command.getValue().hotelId()).isEqualTo(hotelId);
    assertThat(command.getValue().number()).isEqualTo(draft.number());
    assertThat(command.getValue().capacity()).isEqualTo(draft.capacity());
    assertThat(command.getValue().pricePerNight()).isEqualByComparingTo(draft.pricePerNight());
  }

  @Test
  void shouldReturnNotFoundWhenAddingRoomToUnknownHotel() throws Exception {
    // given
    UUID unknownHotelId = UUID.randomUUID();
    given(catalog.addRoom(any())).willThrow(new ResourceNotFoundException("hotel", unknownHotelId));

    // when
    MvcTestResult result = postRoom(unknownHotelId, roomBody(aRoom()));

    // then
    assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
    assertThat(result).contentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(result)
        .bodyJson()
        .extractingPath("$.type")
        .isEqualTo("urn:roomlock:problem:not-found");
    assertThat(result).bodyJson().extractingPath("$.resource").isEqualTo("hotel");
    assertThat(result).bodyJson().extractingPath("$.id").isEqualTo(unknownHotelId.toString());
  }

  @Test
  void shouldReturnConflictWhenRoomNumberIsTakenInHotel() throws Exception {
    // given
    UUID hotelId = UUID.randomUUID();
    RoomDraft draft = aRoom().withHotelId(hotelId);
    given(catalog.addRoom(any()))
        .willReturn(new AddRoomResult.NumberTaken(hotelId, draft.number()));

    // when
    MvcTestResult result = postRoom(hotelId, roomBody(draft));

    // then
    assertThat(result).hasStatus(HttpStatus.CONFLICT);
    assertThat(result).contentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(result)
        .bodyJson()
        .extractingPath("$.type")
        .isEqualTo("urn:roomlock:problem:room-number-taken");
    assertThat(result).bodyJson().extractingPath("$.hotelId").isEqualTo(hotelId.toString());
    assertThat(result).bodyJson().extractingPath("$.number").isEqualTo(draft.number());
  }

  @Test
  void shouldReturnValidationErrorWhenCapacityIsZero() throws Exception {
    // given
    Map<String, Object> body = roomBody(aRoom().withCapacity(0));

    // when
    MvcTestResult result = postRoom(UUID.randomUUID(), body);

    // then
    assertValidationError(result);
    assertThat(result)
        .bodyJson()
        .extractingPath("$.errors[*].field")
        .asArray()
        .contains("capacity");
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "0.00", "-150.00"})
  void shouldReturnValidationErrorWhenPricePerNightIsNotPositive(String price) throws Exception {
    // given
    Map<String, Object> body = roomBody(aRoom().withPricePerNight(new BigDecimal(price)));

    // when
    MvcTestResult result = postRoom(UUID.randomUUID(), body);

    // then
    assertValidationError(result);
    assertThat(result)
        .bodyJson()
        .extractingPath("$.errors[*].field")
        .asArray()
        .contains("pricePerNight");
  }

  @Test
  void shouldReturnValidationErrorWhenPricePerNightHasMoreThanTwoDecimals() throws Exception {
    // given
    Map<String, Object> body = roomBody(aRoom().withPricePerNight(new BigDecimal("150.005")));

    // when
    MvcTestResult result = postRoom(UUID.randomUUID(), body);

    // then
    assertValidationError(result);
    assertThat(result)
        .bodyJson()
        .extractingPath("$.errors[*].field")
        .asArray()
        .contains("pricePerNight");
  }

  @Test
  void shouldReturnValidationErrorWhenNumberIsBlank() throws Exception {
    // given
    Map<String, Object> body = roomBody(aRoom().withNumber(" "));

    // when
    MvcTestResult result = postRoom(UUID.randomUUID(), body);

    // then
    assertValidationError(result);
    assertThat(result).bodyJson().extractingPath("$.errors[*].field").asArray().contains("number");
  }

  @Test
  void shouldReturnRoomWithPriceInTwoDecimalsWhenRoomExists() {
    // given
    Room room = aRoom().withPricePerNight(new BigDecimal("150.00")).build();
    given(catalog.getRoom(room.getId())).willReturn(room);

    // when
    MvcTestResult result = mvc.get().uri(ROOM, room.getId()).exchange();

    // then
    assertThat(result).hasStatus(HttpStatus.OK);
    assertThat(result).bodyJson().extractingPath("$.id").isEqualTo(room.getId().toString());
    assertThat(result)
        .bodyJson()
        .extractingPath("$.hotelId")
        .isEqualTo(room.getHotelId().toString());
    assertThat(result).bodyJson().extractingPath("$.number").isEqualTo(room.getNumber());
    assertThat(result).bodyJson().extractingPath("$.capacity").isEqualTo(room.getCapacity());
    assertThat(result).bodyText().contains("\"pricePerNight\":150.00");
  }

  @Test
  void shouldReturnNotFoundWhenRoomIsUnknown() {
    // given
    UUID unknownId = UUID.randomUUID();
    given(catalog.getRoom(unknownId)).willThrow(new ResourceNotFoundException("room", unknownId));

    // when
    MvcTestResult result = mvc.get().uri(ROOM, unknownId).exchange();

    // then
    assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
    assertThat(result).contentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(result)
        .bodyJson()
        .extractingPath("$.type")
        .isEqualTo("urn:roomlock:problem:not-found");
    assertThat(result).bodyJson().extractingPath("$.resource").isEqualTo("room");
    assertThat(result).bodyJson().extractingPath("$.id").isEqualTo(unknownId.toString());
  }

  private MvcTestResult postRoom(UUID hotelId, Map<String, Object> body) throws Exception {
    return mvc.post()
        .uri(HOTEL_ROOMS, hotelId)
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(body))
        .exchange();
  }

  private static Map<String, Object> roomBody(RoomDraft draft) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("number", draft.number());
    body.put("capacity", draft.capacity());
    body.put("pricePerNight", draft.pricePerNight());
    return body;
  }

  private static void assertValidationError(MvcTestResult result) {
    assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
    assertThat(result).contentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(result)
        .bodyJson()
        .extractingPath("$.type")
        .isEqualTo("urn:roomlock:problem:validation-error");
  }
}
