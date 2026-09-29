package dev.roomlock.booking.api;

import dev.roomlock.booking.domain.Booking;
import dev.roomlock.booking.domain.DateRange;
import dev.roomlock.booking.hold.HoldCommand;
import dev.roomlock.booking.hold.HoldResult;
import dev.roomlock.booking.hold.HoldService;
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
 * {@link HoldResult} to a status: a replay of the same request answers 200, a first hold 201.
 */
@RestController
@RequestMapping(BookingController.BOOKINGS)
@Tag(name = "Bookings", description = "Holding a room for a stay")
class BookingController {

  static final String BOOKINGS = "/api/v1/bookings";

  private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
  private static final int IDEMPOTENCY_KEY_MAX = 64;

  private final HoldService holds;
  private final Problems problems;

  BookingController(HoldService holds, Problems problems) {
    this.holds = holds;
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
