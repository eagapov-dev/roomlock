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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

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
import dev.roomlock.booking.payment.PayResult;
import dev.roomlock.booking.payment.PaymentService;
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
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@WebMvcTest(BookingController.class)
@Import({Problems.class, ApiSecurityConfig.class})
class BookingControllerTest {

  private static final String BOOKINGS = "/api/v1/bookings";
  private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

  @Autowired private MockMvcTester mvc;
  @Autowired private ObjectMapper json;
  @MockitoBean private HoldService holds;
  @MockitoBean private PaymentService payments;
  @MockitoBean private LifecycleService lifecycle;

  /**
   * The chain of {@link ApiSecurityConfig} needs a decoder to be built; no test here sends a real
   * token, {@code jwt()} installs the authentication directly.
   */
  @MockitoBean private JwtDecoder jwtDecoder;

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

  /**
   * The point of the whole change: the body says which room, the token says who. A hold is created
   * for the subject of the token even when the body never mentioned a guest.
   */
  @Test
  void shouldTakeTheGuestIdFromTheTokenWhenHolding() throws Exception {
    // given
    HoldDraft draft = aHold();
    given(holds.hold(any())).willReturn(new HoldResult.Held(bookingOf(draft)));
    assertThat(holdBody(draft)).doesNotContainKey("guestId");

    // when
    postHold(draft);

    // then
    ArgumentCaptor<HoldCommand> command = ArgumentCaptor.forClass(HoldCommand.class);
    then(holds).should().hold(command.capture());
    assertThat(command.getValue().guestId()).isEqualTo(draft.guestId());
  }

  /**
   * Rejected rather than ignored: a client that still sends {@code guestId} was trying to book for
   * somebody, and silently booking for somebody else would be the worse answer of the two.
   */
  @Test
  void shouldReturnValidationErrorWhenBodyStillCarriesGuestId() throws Exception {
    // given
    HoldDraft draft = aHold();
    Map<String, Object> body = holdBody(draft);
    body.put("guestId", UUID.randomUUID());

    // when
    MvcTestResult result = postBody(draft.guestId(), draft.idempotencyKey(), body);

    // then
    assertProblem(result, HttpStatus.BAD_REQUEST, "validation-error");
    assertThat(result).bodyJson().extractingPath("$.errors[*].field").asArray().contains("guestId");
    then(holds).shouldHaveNoInteractions();
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
            .with(guest(draft.guestId()))
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
    MvcTestResult result = postBody(draft.guestId(), draft.idempotencyKey(), body);

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
    MvcTestResult result = postBody(draft.guestId(), draft.idempotencyKey(), body);

    // then
    assertProblem(result, HttpStatus.BAD_REQUEST, "validation-error");
  }

  @Test
  void shouldReturnBookingWhenItExists() {
    // given
    Booking booking = bookingOf(aHold());
    given(holds.getBooking(booking.getId(), booking.getGuestId())).willReturn(booking);

    // when
    MvcTestResult result =
        mvc.get()
            .uri(BOOKINGS + "/{id}", booking.getId())
            .with(guest(booking.getGuestId()))
            .exchange();

    // then
    assertThat(result).hasStatus(HttpStatus.OK);
    assertThat(result).bodyJson().extractingPath("$.id").isEqualTo(booking.getId().toString());
    assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("HELD");
  }

  @Test
  void shouldReturnNotFoundWhenBookingIsUnknown() {
    // given
    UUID unknownId = UUID.randomUUID();
    UUID guestId = UUID.randomUUID();
    given(holds.getBooking(unknownId, guestId))
        .willThrow(new ResourceNotFoundException("booking", unknownId));

    // when
    MvcTestResult result =
        mvc.get().uri(BOOKINGS + "/{id}", unknownId).with(guest(guestId)).exchange();

    // then
    assertProblem(result, HttpStatus.NOT_FOUND, "not-found");
    assertThat(result).bodyJson().extractingPath("$.resource").isEqualTo("booking");
  }

  @Test
  void shouldReturnOkWhenBookingIsCancelled() {
    // given
    Booking booking = bookingOf(aHold());
    given(lifecycle.cancel(booking.getId(), booking.getGuestId()))
        .willReturn(new CancelResult.Cancelled(booking));

    // when
    MvcTestResult result = postCancel(booking.getId(), booking.getGuestId());

    // then
    assertThat(result).hasStatus(HttpStatus.OK);
    assertThat(result).bodyJson().extractingPath("$.id").isEqualTo(booking.getId().toString());
  }

  @Test
  void shouldReturnOkWhenBookingIsAlreadyCancelled() {
    // given
    Booking booking = bookingOf(aHold());
    given(lifecycle.cancel(booking.getId(), booking.getGuestId()))
        .willReturn(new CancelResult.AlreadyCancelled(booking));

    // when
    MvcTestResult result = postCancel(booking.getId(), booking.getGuestId());

    // then
    assertThat(result).hasStatus(HttpStatus.OK);
    assertThat(result).bodyJson().extractingPath("$.id").isEqualTo(booking.getId().toString());
  }

  /** A paid stay is cancelled rather than refused, so the cancel answers 200 for it too. */
  @Test
  void shouldReturnOkWhenConfirmedBookingIsCancelled() {
    // given
    Booking booking = bookingOf(aHold());
    given(lifecycle.cancel(booking.getId(), booking.getGuestId()))
        .willReturn(new CancelResult.Cancelled(booking));

    // when
    MvcTestResult result = postCancel(booking.getId(), booking.getGuestId());

    // then
    assertThat(result).hasStatus(HttpStatus.OK);
    assertThat(result).bodyJson().extractingPath("$.id").isEqualTo(booking.getId().toString());
  }

  @Test
  void shouldReturnConflictWhenBookingIsPaying() {
    // given
    UUID bookingId = UUID.randomUUID();
    UUID guestId = UUID.randomUUID();
    given(lifecycle.cancel(bookingId, guestId))
        .willReturn(new CancelResult.NotCancellable(bookingId, BookingStatus.PAYING));

    // when
    MvcTestResult result = postCancel(bookingId, guestId);

    // then
    assertProblem(result, HttpStatus.CONFLICT, "booking-not-cancellable");
    assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("PAYING");
  }

  @Test
  void shouldReturnNotFoundWhenBookingOfTheCancelIsUnknown() {
    // given
    UUID unknownId = UUID.randomUUID();
    UUID guestId = UUID.randomUUID();
    given(lifecycle.cancel(unknownId, guestId))
        .willThrow(new ResourceNotFoundException("booking", unknownId));

    // when
    MvcTestResult result = postCancel(unknownId, guestId);

    // then
    assertProblem(result, HttpStatus.NOT_FOUND, "not-found");
    assertThat(result).bodyJson().extractingPath("$.resource").isEqualTo("booking");
    assertThat(result).bodyJson().extractingPath("$.id").isEqualTo(unknownId.toString());
  }

  @Test
  void shouldReturnOkWithTheBookingWhenChargeIsApproved() {
    // given
    Booking booking = bookingOf(aHold());
    given(payments.pay(booking.getId(), booking.getGuestId()))
        .willReturn(new PayResult.Confirmed(booking));

    // when
    MvcTestResult result = postPay(booking.getId(), booking.getGuestId());

    // then
    assertThat(result).hasStatus(HttpStatus.OK);
    assertThat(result).bodyJson().extractingPath("$.id").isEqualTo(booking.getId().toString());
  }

  /** A retried request must not charge again, so an already confirmed booking is 200, not 409. */
  @Test
  void shouldReturnOkWhenBookingWasAlreadyConfirmed() {
    // given
    Booking booking = bookingOf(aHold());
    given(payments.pay(booking.getId(), booking.getGuestId()))
        .willReturn(new PayResult.AlreadyConfirmed(booking));

    // when
    MvcTestResult result = postPay(booking.getId(), booking.getGuestId());

    // then
    assertThat(result).hasStatus(HttpStatus.OK);
    assertThat(result).bodyJson().extractingPath("$.id").isEqualTo(booking.getId().toString());
  }

  @Test
  void shouldReturnConflictWithTheReasonWhenChargeIsDeclined() {
    // given
    UUID bookingId = UUID.randomUUID();
    UUID guestId = UUID.randomUUID();
    given(payments.pay(bookingId, guestId))
        .willReturn(new PayResult.Declined(bookingId, "INSUFFICIENT_FUNDS"));

    // when
    MvcTestResult result = postPay(bookingId, guestId);

    // then
    assertProblem(result, HttpStatus.CONFLICT, "payment-declined");
    assertThat(result).bodyJson().extractingPath("$.bookingId").isEqualTo(bookingId.toString());
    assertThat(result).bodyJson().extractingPath("$.reason").isEqualTo("INSUFFICIENT_FUNDS");
  }

  @Test
  void shouldReturnConflictWithTheStatusWhenBookingIsNotPayable() {
    // given
    UUID bookingId = UUID.randomUUID();
    UUID guestId = UUID.randomUUID();
    given(payments.pay(bookingId, guestId))
        .willReturn(new PayResult.NotPayable(bookingId, BookingStatus.CANCELLED));

    // when
    MvcTestResult result = postPay(bookingId, guestId);

    // then
    assertProblem(result, HttpStatus.CONFLICT, "booking-not-payable");
    assertThat(result).bodyJson().extractingPath("$.bookingId").isEqualTo(bookingId.toString());
    assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("CANCELLED");
  }

  /** 503, not 500: the booking stays PAYING and the sweep finishes what this request started. */
  @Test
  void shouldReturnServiceUnavailableWhenProviderGaveNoAnswer() {
    // given
    UUID bookingId = UUID.randomUUID();
    UUID guestId = UUID.randomUUID();
    given(payments.pay(bookingId, guestId))
        .willReturn(new PayResult.ProviderUnavailable(bookingId));

    // when
    MvcTestResult result = postPay(bookingId, guestId);

    // then
    assertProblem(result, HttpStatus.SERVICE_UNAVAILABLE, "payment-unavailable");
    assertThat(result).bodyJson().extractingPath("$.bookingId").isEqualTo(bookingId.toString());
  }

  @Test
  void shouldReturnNotFoundWhenBookingOfThePaymentIsUnknown() {
    // given
    UUID unknownId = UUID.randomUUID();
    UUID guestId = UUID.randomUUID();
    given(payments.pay(unknownId, guestId))
        .willThrow(new ResourceNotFoundException("booking", unknownId));

    // when
    MvcTestResult result = postPay(unknownId, guestId);

    // then
    assertProblem(result, HttpStatus.NOT_FOUND, "not-found");
    assertThat(result).bodyJson().extractingPath("$.resource").isEqualTo("booking");
    assertThat(result).bodyJson().extractingPath("$.id").isEqualTo(unknownId.toString());
  }

  private MvcTestResult postPay(UUID bookingId, UUID guestId) {
    return mvc.post().uri(BOOKINGS + "/{id}/pay", bookingId).with(guest(guestId)).exchange();
  }

  private MvcTestResult postCancel(UUID bookingId, UUID guestId) {
    return mvc.post().uri(BOOKINGS + "/{id}/cancel", bookingId).with(guest(guestId)).exchange();
  }

  /** An authenticated guest: the subject of the token is the guest id the use case will see. */
  private static JwtRequestPostProcessor guest(UUID guestId) {
    return jwt().jwt(token -> token.subject(guestId.toString()));
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
    return postBody(draft.guestId(), draft.idempotencyKey(), holdBody(draft));
  }

  private MvcTestResult postBody(UUID guestId, String idempotencyKey, Map<String, Object> body)
      throws Exception {
    return mvc.post()
        .uri(BOOKINGS)
        .with(guest(guestId))
        .header(IDEMPOTENCY_KEY, idempotencyKey)
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(body))
        .exchange();
  }

  private static Map<String, Object> holdBody(HoldDraft draft) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("roomId", draft.roomId());
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
