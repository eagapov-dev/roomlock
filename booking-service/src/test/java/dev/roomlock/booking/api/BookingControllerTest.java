package dev.roomlock.booking.api;

import static dev.roomlock.booking.support.TestData.HOLD_TTL;
import static dev.roomlock.booking.support.TestData.NOW;
import static dev.roomlock.booking.support.TestData.aBooking;
import static dev.roomlock.booking.support.TestData.aHold;
import static dev.roomlock.booking.support.TestData.november;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.roomlock.booking.domain.Booking;
import dev.roomlock.booking.domain.BookingStatus;
import dev.roomlock.booking.domain.DateRange;
import dev.roomlock.booking.domain.ResourceNotFoundException;
import dev.roomlock.booking.hold.HoldCommand;
import dev.roomlock.booking.hold.HoldResult;
import dev.roomlock.booking.hold.HoldService;
import dev.roomlock.booking.lifecycle.CancelResult;
import dev.roomlock.booking.lifecycle.LifecycleService;
import dev.roomlock.booking.support.TestData.HoldDraft;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@WebMvcTest(BookingController.class)
@Import(Problems.class)
class BookingControllerTest {

  private static final String BOOKINGS = "/api/v1/bookings";
  private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

  @Autowired private MockMvcTester mvc;
  @Autowired private ObjectMapper json;
  @MockitoBean private HoldService holds;
  @MockitoBean private LifecycleService lifecycle;

  @Test
  void shouldReturnCreatedWithLocationWhenRoomIsHeld() throws Exception {
    // given
    HoldDraft draft = aHold();
    Booking booking = bookingOf(draft);
    given(holds.hold(any())).willReturn(new HoldResult.Held(booking));

    // when
    MvcTestResult result = postHold(draft);

    // then
    assertThat(result).hasStatus(HttpStatus.CREATED);
    assertThat(URI.create(result.getResponse().getHeader("Location")).getPath())
        .isEqualTo(BOOKINGS + "/" + booking.getId());
    assertThat(result).bodyJson().extractingPath("$.id").isEqualTo(booking.getId().toString());
    assertThat(result).bodyJson().extractingPath("$.roomId").isEqualTo(draft.roomId().toString());
    assertThat(result).bodyJson().extractingPath("$.guestId").isEqualTo(draft.guestId().toString());
    assertThat(result).bodyJson().extractingPath("$.checkIn").isEqualTo(draft.checkIn().toString());
    assertThat(result)
        .bodyJson()
        .extractingPath("$.checkOut")
        .isEqualTo(draft.checkOut().toString());
    assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("HELD");
    assertThat(result)
        .bodyJson()
        .extractingPath("$.holdExpiresAt")
        .isEqualTo(NOW.plus(HOLD_TTL).toString());
    assertThat(result).bodyText().contains("\"totalPrice\":450.00");
  }

  @Test
  void shouldPassCommandWithIdempotencyKeyOfHeaderWhenHolding() throws Exception {
    // given
    HoldDraft draft = aHold();
    given(holds.hold(any())).willReturn(new HoldResult.Held(bookingOf(draft)));

    // when
    postHold(draft);

    // then
    ArgumentCaptor<HoldCommand> command = ArgumentCaptor.forClass(HoldCommand.class);
    then(holds).should().hold(command.capture());
    assertThat(command.getValue().roomId()).isEqualTo(draft.roomId());
    assertThat(command.getValue().guestId()).isEqualTo(draft.guestId());
    assertThat(command.getValue().stay())
        .isEqualTo(new DateRange(draft.checkIn(), draft.checkOut()));
    assertThat(command.getValue().idempotencyKey()).isEqualTo(draft.idempotencyKey());
  }

  @Test
  void shouldReturnOkWithTheSameBookingWhenRequestIsAReplay() throws Exception {
    // given
    HoldDraft draft = aHold();
    Booking booking = bookingOf(draft);
    given(holds.hold(any())).willReturn(new HoldResult.AlreadyHeld(booking));

    // when
    MvcTestResult result = postHold(draft);

    // then
    assertThat(result).hasStatus(HttpStatus.OK);
    assertThat(result).bodyJson().extractingPath("$.id").isEqualTo(booking.getId().toString());
  }

  @Test
  void shouldReturnConflictWhenRoomIsBusy() throws Exception {
    // given
    HoldDraft draft = aHold();
    given(holds.hold(any()))
        .willReturn(
            new HoldResult.RoomBusy(
                draft.roomId(), new DateRange(draft.checkIn(), draft.checkOut())));

    // when
    MvcTestResult result = postHold(draft);

    // then
    assertProblem(result, HttpStatus.CONFLICT, "room-busy");
    assertThat(result).bodyJson().extractingPath("$.roomId").isEqualTo(draft.roomId().toString());
    assertThat(result).bodyJson().extractingPath("$.checkIn").isEqualTo(draft.checkIn().toString());
    assertThat(result)
        .bodyJson()
        .extractingPath("$.checkOut")
        .isEqualTo(draft.checkOut().toString());
  }

  @Test
  void shouldReturnConflictWhenEveryAttemptLostTheRace() throws Exception {
    // given
    HoldDraft draft = aHold();
    given(holds.hold(any())).willReturn(new HoldResult.RoomContended(draft.roomId()));

    // when
    MvcTestResult result = postHold(draft);

    // then
    assertProblem(result, HttpStatus.CONFLICT, "room-contended");
    assertThat(result).bodyJson().extractingPath("$.roomId").isEqualTo(draft.roomId().toString());
  }

  @Test
  void shouldReturnConflictWhenIdempotencyKeyBelongsToAnotherRequest() throws Exception {
    // given
    HoldDraft draft = aHold();
    given(holds.hold(any()))
        .willReturn(new HoldResult.IdempotencyKeyReused(draft.idempotencyKey()));

    // when
    MvcTestResult result = postHold(draft);

    // then
    assertProblem(result, HttpStatus.CONFLICT, "idempotency-key-reused");
    assertThat(result)
        .bodyJson()
        .extractingPath("$.idempotencyKey")
        .isEqualTo(draft.idempotencyKey());
  }

  @Test
  void shouldReturnNotFoundWhenRoomIsUnknown() throws Exception {
    // given
    HoldDraft draft = aHold();
    given(holds.hold(any())).willThrow(new ResourceNotFoundException("room", draft.roomId()));

    // when
    MvcTestResult result = postHold(draft);

    // then
    assertProblem(result, HttpStatus.NOT_FOUND, "not-found");
    assertThat(result).bodyJson().extractingPath("$.resource").isEqualTo("room");
    assertThat(result).bodyJson().extractingPath("$.id").isEqualTo(draft.roomId().toString());
  }

  @Test
  void shouldReturnValidationErrorWhenIdempotencyKeyHeaderIsMissing() throws Exception {
    // given
    HoldDraft draft = aHold();

    // when
    MvcTestResult result =
        mvc.post()
            .uri(BOOKINGS)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(holdBody(draft)))
            .exchange();

    // then
    assertProblem(result, HttpStatus.BAD_REQUEST, "validation-error");
    assertThat(result)
        .bodyJson()
        .extractingPath("$.errors[*].field")
        .asArray()
        .contains(IDEMPOTENCY_KEY);
  }

  @Test
  void shouldReturnValidationErrorWhenIdempotencyKeyIsLongerThanColumn() throws Exception {
    // given
    HoldDraft draft = aHold().withIdempotencyKey("k".repeat(65));

    // when
    MvcTestResult result = postHold(draft);

    // then
    assertProblem(result, HttpStatus.BAD_REQUEST, "validation-error");
    assertThat(result)
        .bodyJson()
        .extractingPath("$.errors[*].field")
        .asArray()
        .contains(IDEMPOTENCY_KEY);
  }

  @Test
  void shouldReturnValidationErrorWhenCheckOutIsNotAfterCheckIn() throws Exception {
    // given
    HoldDraft draft = aHold().withStay(november(10), november(10));

    // when
    MvcTestResult result = postHold(draft);

    // then
    assertProblem(result, HttpStatus.BAD_REQUEST, "validation-error");
    assertThat(result)
        .bodyJson()
        .extractingPath("$.errors[*].field")
        .asArray()
        .contains("checkOut");
  }

  @Test
  void shouldReturnValidationErrorWhenRoomIdIsMissing() throws Exception {
    // given
    HoldDraft draft = aHold();
    Map<String, Object> body = holdBody(draft);
    body.remove("roomId");

    // when
    MvcTestResult result = postBody(draft.idempotencyKey(), body);

    // then
    assertProblem(result, HttpStatus.BAD_REQUEST, "validation-error");
    assertThat(result).bodyJson().extractingPath("$.errors[*].field").asArray().contains("roomId");
  }

  @Test
  void shouldReturnValidationErrorWhenCheckInIsNotADate() throws Exception {
    // given
    HoldDraft draft = aHold();
    Map<String, Object> body = holdBody(draft);
    body.put("checkIn", "2026-13-45");

    // when
    MvcTestResult result = postBody(draft.idempotencyKey(), body);

    // then
    assertProblem(result, HttpStatus.BAD_REQUEST, "validation-error");
  }

  @Test
  void shouldReturnBookingWhenItExists() {
    // given
    Booking booking = bookingOf(aHold());
    given(holds.getBooking(booking.getId())).willReturn(booking);

    // when
    MvcTestResult result = mvc.get().uri(BOOKINGS + "/{id}", booking.getId()).exchange();

    // then
    assertThat(result).hasStatus(HttpStatus.OK);
    assertThat(result).bodyJson().extractingPath("$.id").isEqualTo(booking.getId().toString());
    assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("HELD");
  }

  @Test
  void shouldReturnNotFoundWhenBookingIsUnknown() {
    // given
    UUID unknownId = UUID.randomUUID();
    given(holds.getBooking(unknownId))
        .willThrow(new ResourceNotFoundException("booking", unknownId));

    // when
    MvcTestResult result = mvc.get().uri(BOOKINGS + "/{id}", unknownId).exchange();

    // then
    assertProblem(result, HttpStatus.NOT_FOUND, "not-found");
    assertThat(result).bodyJson().extractingPath("$.resource").isEqualTo("booking");
  }

  @Test
  void shouldReturnOkWhenBookingIsCancelled() {
    // given
    Booking booking = bookingOf(aHold());
    given(lifecycle.cancel(booking.getId())).willReturn(new CancelResult.Cancelled(booking));

    // when
    MvcTestResult result = postCancel(booking.getId());

    // then
    assertThat(result).hasStatus(HttpStatus.OK);
    assertThat(result).bodyJson().extractingPath("$.id").isEqualTo(booking.getId().toString());
  }

  @Test
  void shouldReturnOkWhenBookingIsAlreadyCancelled() {
    // given
    Booking booking = bookingOf(aHold());
    given(lifecycle.cancel(booking.getId())).willReturn(new CancelResult.AlreadyCancelled(booking));

    // when
    MvcTestResult result = postCancel(booking.getId());

    // then
    assertThat(result).hasStatus(HttpStatus.OK);
    assertThat(result).bodyJson().extractingPath("$.id").isEqualTo(booking.getId().toString());
  }

  @Test
  void shouldReturnConflictWhenBookingIsConfirmed() {
    // given
    UUID bookingId = UUID.randomUUID();
    given(lifecycle.cancel(bookingId))
        .willReturn(new CancelResult.NotCancellable(bookingId, BookingStatus.CONFIRMED));

    // when
    MvcTestResult result = postCancel(bookingId);

    // then
    assertProblem(result, HttpStatus.CONFLICT, "booking-not-cancellable");
    assertThat(result).bodyJson().extractingPath("$.bookingId").isEqualTo(bookingId.toString());
    assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("CONFIRMED");
  }

  @Test
  void shouldReturnConflictWhenBookingIsPaying() {
    // given
    UUID bookingId = UUID.randomUUID();
    given(lifecycle.cancel(bookingId))
        .willReturn(new CancelResult.NotCancellable(bookingId, BookingStatus.PAYING));

    // when
    MvcTestResult result = postCancel(bookingId);

    // then
    assertProblem(result, HttpStatus.CONFLICT, "booking-not-cancellable");
    assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("PAYING");
  }

  @Test
  void shouldReturnNotFoundWhenBookingOfTheCancelIsUnknown() {
    // given
    UUID unknownId = UUID.randomUUID();
    given(lifecycle.cancel(unknownId))
        .willThrow(new ResourceNotFoundException("booking", unknownId));

    // when
    MvcTestResult result = postCancel(unknownId);

    // then
    assertProblem(result, HttpStatus.NOT_FOUND, "not-found");
    assertThat(result).bodyJson().extractingPath("$.resource").isEqualTo("booking");
    assertThat(result).bodyJson().extractingPath("$.id").isEqualTo(unknownId.toString());
  }

  private MvcTestResult postCancel(UUID bookingId) {
    return mvc.post().uri(BOOKINGS + "/{id}/cancel", bookingId).exchange();
  }

  private static Booking bookingOf(HoldDraft draft) {
    return aBooking()
        .withRoomId(draft.roomId())
        .withGuestId(draft.guestId())
        .withStay(draft.checkIn(), draft.checkOut())
        .withIdempotencyKey(draft.idempotencyKey())
        .build();
  }

  private MvcTestResult postHold(HoldDraft draft) throws Exception {
    return postBody(draft.idempotencyKey(), holdBody(draft));
  }

  private MvcTestResult postBody(String idempotencyKey, Map<String, Object> body) throws Exception {
    return mvc.post()
        .uri(BOOKINGS)
        .header(IDEMPOTENCY_KEY, idempotencyKey)
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(body))
        .exchange();
  }

  private static Map<String, Object> holdBody(HoldDraft draft) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("roomId", draft.roomId());
    body.put("guestId", draft.guestId());
    body.put("checkIn", draft.checkIn().toString());
    body.put("checkOut", draft.checkOut().toString());
    return body;
  }

  private static void assertProblem(MvcTestResult result, HttpStatus status, String type) {
    assertThat(result).hasStatus(status);
    assertThat(result).contentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(result)
        .bodyJson()
        .extractingPath("$.type")
        .isEqualTo("urn:roomlock:problem:" + type);
  }
}
