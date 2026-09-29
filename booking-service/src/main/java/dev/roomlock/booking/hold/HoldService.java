package dev.roomlock.booking.hold;

import dev.roomlock.booking.domain.Booking;
import dev.roomlock.booking.domain.DateRange;
import dev.roomlock.booking.domain.ResourceNotFoundException;
import dev.roomlock.booking.domain.Room;
import dev.roomlock.booking.infra.BookingRepository;
import dev.roomlock.booking.infra.RoomRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Room hold use cases. */
@Service
public class HoldService {

  private static final String ROOM = "room";
  private static final String BOOKING = "booking";

  private static final Logger log = LoggerFactory.getLogger(HoldService.class);

  private final RoomRepository rooms;
  private final BookingRepository bookings;
  private final HoldStrategies strategies;
  private final Duration holdTtl;
  private final Clock clock;

  public HoldService(
      RoomRepository rooms,
      BookingRepository bookings,
      HoldStrategies strategies,
      HoldProperties properties,
      Clock clock) {
    this.rooms = rooms;
    this.bookings = bookings;
    this.strategies = strategies;
    this.holdTtl = properties.ttl();
    this.clock = clock;
  }

  /**
   * Holds the room for the stay of the command.
   *
   * <p>Not transactional on purpose: the write belongs to the strategy, which opens a transaction
   * per attempt and rolls a lost one back before trying again.
   */
  public HoldResult hold(HoldCommand command) {
    Room room =
        rooms
            .findById(command.roomId())
            .orElseThrow(() -> new ResourceNotFoundException(ROOM, command.roomId()));
    String requestHash = RequestHash.of(command);
    Optional<Booking> previous =
        bookings.findByGuestIdAndIdempotencyKey(command.guestId(), command.idempotencyKey());
    if (previous.isPresent()) {
      return replayOf(previous.get(), requestHash, command);
    }
    return resultOf(command, strategies.active().hold(attemptOf(command, room, requestHash)));
  }

  @Transactional(readOnly = true)
  public Booking getBooking(UUID bookingId) {
    return bookings
        .findById(bookingId)
        .orElseThrow(() -> new ResourceNotFoundException(BOOKING, bookingId));
  }

  private HoldResult resultOf(HoldCommand command, HoldOutcome outcome) {
    return switch (outcome) {
      case HoldOutcome.Inserted inserted -> new HoldResult.Held(inserted.booking());
      case HoldOutcome.Busy busy -> new HoldResult.RoomBusy(command.roomId(), command.stay());
      case HoldOutcome.Contended contended -> new HoldResult.RoomContended(command.roomId());
      case HoldOutcome.KeyTaken keyTaken -> replayOfRace(command);
    };
  }

  private HoldAttempt attemptOf(HoldCommand command, Room room, String requestHash) {
    Instant now = clock.instant();
    return new HoldAttempt(
        command.roomId(),
        command.guestId(),
        command.stay(),
        totalPrice(room, command.stay()),
        command.idempotencyKey(),
        requestHash,
        now.plus(holdTtl),
        now);
  }

  private static BigDecimal totalPrice(Room room, DateRange stay) {
    return room.getPricePerNight().multiply(BigDecimal.valueOf(stay.nights()));
  }

  /** A retry is a replay only when it carries the same request as the first one (rule I-2). */
  private static HoldResult replayOf(Booking booking, String requestHash, HoldCommand command) {
    return booking.getRequestHash().equals(requestHash)
        ? new HoldResult.AlreadyHeld(booking)
        : new HoldResult.IdempotencyKeyReused(command.idempotencyKey());
  }

  /** A request with the same key won the race: its booking is the answer to this one as well. */
  private HoldResult replayOfRace(HoldCommand command) {
    return bookings
        .findByGuestIdAndIdempotencyKey(command.guestId(), command.idempotencyKey())
        .map(booking -> replayOf(booking, RequestHash.of(command), command))
        .orElseGet(
            () -> {
              log.warn("Idempotency key of room {} was taken and then released", command.roomId());
              return new HoldResult.RoomContended(command.roomId());
            });
  }
}
