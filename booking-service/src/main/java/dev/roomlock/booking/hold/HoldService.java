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
    HoldOutcome outcome = strategies.active().hold(attemptOf(command, room, requestHash));
    return resultOf(command, requestHash, outcome);
  }

  /**
   * The booking of this guest. A booking of somebody else is answered as if it did not exist (rule
   * E-4): 404 and not 403, because a 403 tells a caller that guessed the id that the id is real.
   */
  @Transactional(readOnly = true)
  public Booking getBooking(UUID bookingId, UUID guestId) {
    return bookings
        .findByIdAndGuestId(bookingId, guestId)
        .orElseThrow(() -> new ResourceNotFoundException(BOOKING, bookingId));
  }

  private HoldResult resultOf(HoldCommand command, String requestHash, HoldOutcome outcome) {
    return switch (outcome) {
      case HoldOutcome.Inserted inserted -> new HoldResult.Held(inserted.booking());
      case HoldOutcome.Busy busy -> busyOrReplay(command, requestHash);
      case HoldOutcome.Contended contended -> new HoldResult.RoomContended(command.roomId());
      case HoldOutcome.KeyTaken keyTaken -> replayOfRace(command, requestHash);
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

  /**
   * A room reported busy is only busy while no request of this key holds it.
   *
   * <p>Two simultaneous holds of the same guest and the same key make the loser's row break {@code
   * booking_guest_id_idempotency_key_key} and {@code booking_no_overlap} at once, and PostgreSQL
   * names whichever of the two indexes it happened to check first. Believing that name would answer
   * 409 {@code room-busy} to a client that already has the booking, so the question is asked
   * instead: a booking under this key means a request of this key won and its answer is replayed;
   * nothing under it means the room is held by somebody else.
   *
   * <p>Asked here rather than in the strategies because idempotency is the business of this use
   * case and the strategies know nothing about keys - and because PESSIMISTIC and OPTIMISTIC owe
   * the same replay, although their busy comes from their own overlap check rather than from a
   * constraint.
   */
  private HoldResult busyOrReplay(HoldCommand command, String requestHash) {
    return replayUnderKey(command, requestHash)
        .orElseGet(() -> new HoldResult.RoomBusy(command.roomId(), command.stay()));
  }

  /** A request with the same key won the race: its booking is the answer to this one as well. */
  private HoldResult replayOfRace(HoldCommand command, String requestHash) {
    return replayUnderKey(command, requestHash)
        .orElseGet(
            () -> {
              log.warn("Idempotency key of room {} was taken and then released", command.roomId());
              return new HoldResult.RoomContended(command.roomId());
            });
  }

  /** The answer the booking of this guest and key carries, if the key has one by now. */
  private Optional<HoldResult> replayUnderKey(HoldCommand command, String requestHash) {
    return bookings
        .findByGuestIdAndIdempotencyKey(command.guestId(), command.idempotencyKey())
        .map(booking -> replayOf(booking, requestHash, command));
  }
}
