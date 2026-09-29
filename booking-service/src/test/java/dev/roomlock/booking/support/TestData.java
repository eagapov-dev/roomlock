package dev.roomlock.booking.support;

import dev.roomlock.booking.domain.Booking;
import dev.roomlock.booking.domain.BookingStatus;
import dev.roomlock.booking.domain.CancelReason;
import dev.roomlock.booking.domain.DateRange;
import dev.roomlock.booking.domain.Hotel;
import dev.roomlock.booking.domain.Room;
import dev.roomlock.booking.hold.HoldAttempt;
import dev.roomlock.booking.hold.HoldCommand;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.lang.Nullable;

/** Test data factories with defaults and {@code with...} modifiers (rule T-9). */
public final class TestData {

  /** Start of {@link MutableClock} and the moment every default row is created at. */
  public static final Instant NOW = Instant.parse("2026-11-01T09:00:00Z");

  /** Default hold TTL, matches {@code roomlock.hold.ttl}. */
  public static final Duration HOLD_TTL = Duration.ofMinutes(10);

  private static final int STAY_YEAR = 2026;
  private static final String REQUEST_HASH = "a".repeat(64);
  private static final BigDecimal PRICE_PER_NIGHT = new BigDecimal("150.00");
  private static final BigDecimal TOTAL_PRICE = new BigDecimal("450.00");

  private TestData() {}

  /** A day of November of the test year: {@code november(10)} is the default check-in. */
  public static LocalDate november(int day) {
    return LocalDate.of(STAY_YEAR, 11, day);
  }

  public static HotelDraft aHotel() {
    return new HotelDraft("Hotel Wawel", "Krakow");
  }

  public static RoomDraft aRoom() {
    return new RoomDraft(UUID.randomUUID(), "101", 2, PRICE_PER_NIGHT);
  }

  /** Hold of 10-13 November by a fresh guest with a fresh idempotency key. */
  public static HoldDraft aHold() {
    return new HoldDraft(
        UUID.randomUUID(), UUID.randomUUID(), november(10), november(13), newIdempotencyKey());
  }

  /** A key no other request has used. */
  public static String newIdempotencyKey() {
    return UUID.randomUUID().toString();
  }

  /** HELD booking 10-13 November of a fresh guest with a fresh idempotency key. */
  public static BookingDraft aBooking() {
    return new BookingDraft(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        november(10),
        november(13),
        BookingStatus.HELD,
        null,
        new BigDecimal("450.00"),
        UUID.randomUUID().toString(),
        REQUEST_HASH,
        NOW.plus(HOLD_TTL),
        NOW);
  }

  public record HoldDraft(
      UUID roomId, UUID guestId, LocalDate checkIn, LocalDate checkOut, String idempotencyKey) {

    public HoldDraft withRoomId(UUID newRoomId) {
      return new HoldDraft(newRoomId, guestId, checkIn, checkOut, idempotencyKey);
    }

    public HoldDraft withGuestId(UUID newGuestId) {
      return new HoldDraft(roomId, newGuestId, checkIn, checkOut, idempotencyKey);
    }

    public HoldDraft withStay(LocalDate newCheckIn, LocalDate newCheckOut) {
      return new HoldDraft(roomId, guestId, newCheckIn, newCheckOut, idempotencyKey);
    }

    public HoldDraft withIdempotencyKey(String newIdempotencyKey) {
      return new HoldDraft(roomId, guestId, checkIn, checkOut, newIdempotencyKey);
    }

    public HoldCommand build() {
      return new HoldCommand(roomId, guestId, new DateRange(checkIn, checkOut), idempotencyKey);
    }

    /**
     * The prepared write a {@link dev.roomlock.booking.hold.HoldStrategy} receives, with the price,
     * the request hash and the hold window the use case would have computed.
     */
    public HoldAttempt attempt() {
      return new HoldAttempt(
          roomId,
          guestId,
          new DateRange(checkIn, checkOut),
          TOTAL_PRICE,
          idempotencyKey,
          REQUEST_HASH,
          NOW.plus(HOLD_TTL),
          NOW);
    }
  }

  public record HotelDraft(String name, String city) {

    public HotelDraft withName(String newName) {
      return new HotelDraft(newName, city);
    }

    public HotelDraft withCity(String newCity) {
      return new HotelDraft(name, newCity);
    }

    public Hotel build() {
      return Hotel.create(name, city);
    }
  }

  public record RoomDraft(UUID hotelId, String number, int capacity, BigDecimal pricePerNight) {

    public RoomDraft withHotelId(UUID newHotelId) {
      return new RoomDraft(newHotelId, number, capacity, pricePerNight);
    }

    public RoomDraft withNumber(String newNumber) {
      return new RoomDraft(hotelId, newNumber, capacity, pricePerNight);
    }

    public RoomDraft withCapacity(int newCapacity) {
      return new RoomDraft(hotelId, number, newCapacity, pricePerNight);
    }

    public RoomDraft withPricePerNight(BigDecimal newPricePerNight) {
      return new RoomDraft(hotelId, number, capacity, newPricePerNight);
    }

    public Room build() {
      return Room.create(hotelId, number, capacity, pricePerNight);
    }
  }

  /**
   * A booking row. {@link #build()} goes through the entity factory (HELD only, rule DB-5); {@link
   * BookingTable#insert(BookingDraft)} writes any status with plain SQL.
   */
  public record BookingDraft(
      UUID id,
      UUID roomId,
      UUID guestId,
      LocalDate checkIn,
      LocalDate checkOut,
      BookingStatus status,
      @Nullable CancelReason cancelReason,
      BigDecimal totalPrice,
      String idempotencyKey,
      String requestHash,
      Instant holdExpiresAt,
      Instant createdAt) {

    public BookingDraft withRoomId(UUID newRoomId) {
      return new BookingDraft(
          id,
          newRoomId,
          guestId,
          checkIn,
          checkOut,
          status,
          cancelReason,
          totalPrice,
          idempotencyKey,
          requestHash,
          holdExpiresAt,
          createdAt);
    }

    public BookingDraft withGuestId(UUID newGuestId) {
      return new BookingDraft(
          id,
          roomId,
          newGuestId,
          checkIn,
          checkOut,
          status,
          cancelReason,
          totalPrice,
          idempotencyKey,
          requestHash,
          holdExpiresAt,
          createdAt);
    }

    public BookingDraft withStay(LocalDate newCheckIn, LocalDate newCheckOut) {
      return new BookingDraft(
          id,
          roomId,
          guestId,
          newCheckIn,
          newCheckOut,
          status,
          cancelReason,
          totalPrice,
          idempotencyKey,
          requestHash,
          holdExpiresAt,
          createdAt);
    }

    /**
     * Changes the status; CANCELLED gets USER_CANCELLED, any other status no reason, as transitions
     * do.
     */
    public BookingDraft withStatus(BookingStatus newStatus) {
      return new BookingDraft(
          id,
          roomId,
          guestId,
          checkIn,
          checkOut,
          newStatus,
          newStatus == BookingStatus.CANCELLED ? CancelReason.USER_CANCELLED : null,
          totalPrice,
          idempotencyKey,
          requestHash,
          holdExpiresAt,
          createdAt);
    }

    /** Overrides the reason alone, to build rows that break the status/reason consistency. */
    public BookingDraft withCancelReason(@Nullable CancelReason newCancelReason) {
      return new BookingDraft(
          id,
          roomId,
          guestId,
          checkIn,
          checkOut,
          status,
          newCancelReason,
          totalPrice,
          idempotencyKey,
          requestHash,
          holdExpiresAt,
          createdAt);
    }

    public BookingDraft withIdempotencyKey(String newIdempotencyKey) {
      return new BookingDraft(
          id,
          roomId,
          guestId,
          checkIn,
          checkOut,
          status,
          cancelReason,
          totalPrice,
          newIdempotencyKey,
          requestHash,
          holdExpiresAt,
          createdAt);
    }

    /** A copy with a new row id, for inserting the same data twice. */
    public BookingDraft withNewId() {
      return new BookingDraft(
          UUID.randomUUID(),
          roomId,
          guestId,
          checkIn,
          checkOut,
          status,
          cancelReason,
          totalPrice,
          idempotencyKey,
          requestHash,
          holdExpiresAt,
          createdAt);
    }

    /** Set once a booking has entered PAYING, as the lifecycle transitions do. */
    public @Nullable Instant paymentStartedAt() {
      return status == BookingStatus.PAYING || status == BookingStatus.CONFIRMED ? createdAt : null;
    }

    /** A new HELD entity with these values and an id generated by the entity factory. */
    public Booking build() {
      if (status != BookingStatus.HELD) {
        throw new IllegalStateException("The entity factory creates HELD bookings only");
      }
      return Booking.hold(
          roomId,
          guestId,
          new DateRange(checkIn, checkOut),
          totalPrice,
          idempotencyKey,
          requestHash,
          holdExpiresAt,
          createdAt);
    }
  }
}
