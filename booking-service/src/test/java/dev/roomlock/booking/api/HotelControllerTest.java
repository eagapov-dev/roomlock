package dev.roomlock.booking.api;

import static dev.roomlock.booking.support.TestData.aHotel;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.roomlock.booking.catalog.CatalogService;
import dev.roomlock.booking.catalog.CreateHotelCommand;
import dev.roomlock.booking.domain.Hotel;
import dev.roomlock.booking.domain.ResourceNotFoundException;
import dev.roomlock.booking.support.TestData.HotelDraft;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@WebMvcTest(HotelController.class)
@Import(Problems.class)
class HotelControllerTest {

  private static final String HOTELS = "/api/v1/hotels";

  @Autowired private MockMvcTester mvc;
  @Autowired private ObjectMapper json;
  @MockitoBean private CatalogService catalog;

  @Test
  void shouldReturnCreatedWithLocationWhenHotelIsValid() throws Exception {
    // given
    HotelDraft draft = aHotel();
    Hotel hotel = draft.build();
    given(catalog.createHotel(new CreateHotelCommand(draft.name(), draft.city())))
        .willReturn(hotel);

    // when
    MvcTestResult result = postHotel(Map.of("name", draft.name(), "city", draft.city()));

    // then
    assertThat(result).hasStatus(HttpStatus.CREATED);
    assertThat(URI.create(result.getResponse().getHeader("Location")).getPath())
        .isEqualTo(HOTELS + "/" + hotel.getId());
    assertThat(result).bodyJson().extractingPath("$.id").isEqualTo(hotel.getId().toString());
  }

  @Test
  void shouldReturnValidationErrorWhenNameIsMissing() throws Exception {
    // given
    Map<String, Object> body = Map.of("city", aHotel().city());

    // when
    MvcTestResult result = postHotel(body);

    // then
    assertValidationError(result);
    assertThat(result).bodyJson().extractingPath("$.errors[*].field").asArray().contains("name");
  }

  @Test
  void shouldReturnValidationErrorWhenNameIsLongerThanColumn() throws Exception {
    // given
    Map<String, Object> body = Map.of("name", "x".repeat(201), "city", aHotel().city());

    // when
    MvcTestResult result = postHotel(body);

    // then
    assertValidationError(result);
    assertThat(result).bodyJson().extractingPath("$.errors[*].field").asArray().contains("name");
  }

  @Test
  void shouldReturnValidationErrorWhenJsonIsMalformed() {
    // given
    String malformed = "{\"name\": \"Hotel Wawel\", ";

    // when
    MvcTestResult result =
        mvc.post()
            .uri(HOTELS)
            .contentType(MediaType.APPLICATION_JSON)
            .content(malformed)
            .exchange();

    // then
    assertValidationError(result);
  }

  @Test
  void shouldReturnHotelWhenItExists() {
    // given
    Hotel hotel = aHotel().build();
    given(catalog.getHotel(hotel.getId())).willReturn(hotel);

    // when
    MvcTestResult result = mvc.get().uri(HOTELS + "/{id}", hotel.getId()).exchange();

    // then
    assertThat(result).hasStatus(HttpStatus.OK);
    assertThat(result).bodyJson().extractingPath("$.id").isEqualTo(hotel.getId().toString());
    assertThat(result).bodyJson().extractingPath("$.name").isEqualTo(hotel.getName());
    assertThat(result).bodyJson().extractingPath("$.city").isEqualTo(hotel.getCity());
  }

  @Test
  void shouldReturnNotFoundWhenHotelIsUnknown() {
    // given
    UUID unknownId = UUID.randomUUID();
    given(catalog.getHotel(unknownId)).willThrow(new ResourceNotFoundException("hotel", unknownId));

    // when
    MvcTestResult result = mvc.get().uri(HOTELS + "/{id}", unknownId).exchange();

    // then
    assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
    assertThat(result).contentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(result)
        .bodyJson()
        .extractingPath("$.type")
        .isEqualTo("urn:roomlock:problem:not-found");
    assertThat(result).bodyJson().extractingPath("$.resource").isEqualTo("hotel");
    assertThat(result).bodyJson().extractingPath("$.id").isEqualTo(unknownId.toString());
  }

  @Test
  void shouldReturnValidationErrorWhenHotelIdIsNotUuid() {
    // given
    String notUuid = "not-a-uuid";

    // when
    MvcTestResult result = mvc.get().uri(HOTELS + "/{id}", notUuid).exchange();

    // then
    assertValidationError(result);
    assertThat(result).bodyJson().extractingPath("$.errors[*].field").asArray().contains("id");
  }

  @Test
  void shouldReturnInternalErrorWithoutDetailsWhenServiceFailsUnexpectedly() {
    // given
    UUID hotelId = UUID.randomUUID();
    String secret = "relation \"hotel\" does not exist";
    given(catalog.getHotel(any())).willThrow(new IllegalStateException(secret));

    // when
    MvcTestResult result = mvc.get().uri(HOTELS + "/{id}", hotelId).exchange();

    // then
    assertThat(result).hasStatus(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(result).contentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(result)
        .bodyJson()
        .extractingPath("$.type")
        .isEqualTo("urn:roomlock:problem:internal-error");
    assertThat(result)
        .bodyText()
        .doesNotContain(secret)
        .doesNotContain("IllegalStateException")
        .doesNotContain("at dev.roomlock");
  }

  private MvcTestResult postHotel(Map<String, Object> body) throws Exception {
    return mvc.post()
        .uri(HOTELS)
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(body))
        .exchange();
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
