package dev.roomlock.booking.api;

import dev.roomlock.booking.domain.Booking;
import dev.roomlock.booking.domain.DateRange;
import dev.roomlock.booking.hold.HoldCommand;
import dev.roomlock.booking.hold.HoldResult;
import dev.roomlock.booking.hold.HoldService;
import dev.roomlock.booking.lifecycle.CancelResult;
import dev.roomlock.booking.lifecycle.LifecycleService;
import dev.roomlock.booking.payment.PayResult;
import dev.roomlock.booking.payment.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Bookings of a room. The controller only maps HTTP to the use case (rule A-2) and every outcome of
 * {@link HoldResult}, {@link PayResult} and {@link CancelResult} to a status: a replay of the same
 * request answers 200, a first hold 201, a booking that can no longer be paid for or cancelled 409,
 * and a payment provider that gave no answer 503.
 */
@RestController
@RequestMapping(BookingController.BOOKINGS)
@Tag(
    name = "Bookings",
    description = "Holding a room for a stay, paying for it and cancelling a booking")
class BookingController {

  static final String BOOKINGS = "/api/v1/bookings";

  private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
  private static final int IDEMPOTENCY_KEY_MAX = 64;

  private final HoldService holds;
  private final PaymentService payments;
  private final LifecycleService lifecycle;
  private final Problems problems;

  BookingController(
      HoldService holds, PaymentService payments, LifecycleService lifecycle, Problems problems) {
    this.holds = holds;
    this.payments = payments;
    this.lifecycle = lifecycle;
    this.problems = problems;
  }

  @PostMapping
  @Operation(summary = "Hold a room for a stay")
  ResponseEntity<?> hold(
      @RequestHeader(name = IDEMPOTENCY_KEY) @NotBlank @Size(max = IDEMPOTENCY_KEY_MAX)
          String idempotencyKey,
      @Valid @RequestBody HoldBookingRequest request) {
    HoldResult result =
        holds.hold(
            new HoldCommand(
                request.roomId(),
                request.guestId(),
                new DateRange(request.checkIn(), request.checkOut()),
                idempotencyKey));
    return switch (result) {
      case HoldResult.Held(Booking booking) ->
          ResponseEntity.created(location(booking)).body(BookingResponse.of(booking));
      case HoldResult.AlreadyHeld(Booking booking) ->
          ResponseEntity.ok(BookingResponse.of(booking));
      case HoldResult.RoomBusy busy -> conflict(problems.roomBusy(busy.roomId(), busy.stay()));
      case HoldResult.RoomContended contended ->
          conflict(problems.roomContended(contended.roomId()));
      case HoldResult.IdempotencyKeyReused reused ->
          conflict(problems.idempotencyKeyReused(reused.idempotencyKey()));
    };
  }

  /**
   * No request body: the amount comes from the booking, and payment-service is idempotent on the
   * booking id, so a client that sends this twice is answered the same way and charged once.
   */
  @PostMapping("/{id}/pay")
  @Operation(summary = "Pay for a booking")
  ResponseEntity<?> pay(@PathVariable UUID id) {
    return switch (payments.pay(id)) {
      case PayResult.Confirmed(Booking booking) -> ResponseEntity.ok(BookingResponse.of(booking));
      case PayResult.AlreadyConfirmed(Booking booking) ->
          ResponseEntity.ok(BookingResponse.of(booking));
      case PayResult.Declined declined ->
          conflict(problems.paymentDeclined(declined.bookingId(), declined.reason()));
      case PayResult.NotPayable refused ->
          conflict(problems.bookingNotPayable(refused.bookingId(), refused.status()));
      case PayResult.ProviderUnavailable pending ->
          ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
              .body(problems.paymentUnavailable(pending.bookingId()));
    };
  }

  /** Cancelling an already cancelled booking is the same answer again, not a failure. */
  @PostMapping("/{id}/cancel")
  @Operation(summary = "Cancel a booking")
  ResponseEntity<?> cancel(@PathVariable UUID id) {
    return switch (lifecycle.cancel(id)) {
      case CancelResult.Cancelled(Booking booking) ->
          ResponseEntity.ok(BookingResponse.of(booking));
      case CancelResult.AlreadyCancelled(Booking booking) ->
          ResponseEntity.ok(BookingResponse.of(booking));
      case CancelResult.NotCancellable refused ->
          conflict(problems.bookingNotCancellable(refused.bookingId(), refused.status()));
    };
  }

  @GetMapping("/{id}")
  @Operation(summary = "Read a booking")
  BookingResponse get(@PathVariable UUID id) {
    return BookingResponse.of(holds.getBooking(id));
  }

  private static ResponseEntity<ProblemDetail> conflict(ProblemDetail problem) {
    return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
  }

  private static URI location(Booking booking) {
    return ServletUriComponentsBuilder.fromCurrentRequest()
        .path("/{id}")
        .buildAndExpand(booking.getId())
        .toUri();
  }
}
