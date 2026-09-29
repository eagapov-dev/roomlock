package dev.roomlock.booking.infra;

import static dev.roomlock.booking.support.TestData.NOW;
import static dev.roomlock.booking.support.TestData.aBooking;
import static dev.roomlock.booking.support.TestData.aHotel;
import static dev.roomlock.booking.support.TestData.aRoom;
import static org.assertj.core.api.Assertions.assertThat;

import dev.roomlock.booking.domain.BookingStatus;
import dev.roomlock.booking.support.BookingJpaTest;
import dev.roomlock.booking.support.BookingTable;
import dev.roomlock.booking.support.DatabaseCleaner;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The conditional UPDATEs of the payment flow (rule DB-5): each one matches exactly the status it
 * is a transition from, so a row a concurrent writer has moved keeps what it has.
 */
@BookingJpaTest
class PaymentTransitionSqlTest {

  private static final Instant LATER = NOW.plus(Duration.ofMinutes(5));
  private static final int BATCH = 10;

  @Autowired private DatabaseCleaner databaseCleaner;
  @Autowired private BookingTable bookingTable;
  @Autowired private BookingRepository bookings;
  @Autowired private HotelRepository hotels;
  @Autowired private RoomRepository rooms;

  private UUID roomId;

  @BeforeEach
  void setUp() {
    databaseCleaner.truncateAll();
    UUID hotelId = hotels.save(aHotel().build()).getId();
    roomId = rooms.save(aRoom().withHotelId(hotelId).build()).getId();
  }

  @Test
  void shouldTakeBookingIntoPayingAndStampItWhenBookingIsHeld() {
    // given
    UUID bookingId = insert(BookingStatus.HELD);

    // when
    int updated = bookings.startPaying(bookingId, LATER);

    // then
    assertThat(updated).isEqualTo(1);
    assertThat(bookingTable.status(bookingId)).isEqualTo("PAYING");
    assertThat(bookingTable.paymentStartedAt(bookingId)).contains(LATER);
  }

  @ParameterizedTest
  @EnumSource(
      value = BookingStatus.class,
      names = {"PAYING", "CONFIRMED", "CANCELLED"})
  void shouldNotTakeBookingIntoPayingWhenItIsNotHeld(BookingStatus status) {
    // given
    UUID bookingId = insert(status);

    // when
    int updated = bookings.startPaying(bookingId, LATER);

    // then
    assertThat(updated).isZero();
    assertThat(bookingTable.status(bookingId)).isEqualTo(status.name());
  }

  @Test
  void shouldConfirmBookingWhenItIsPaying() {
    // given
    UUID bookingId = insert(BookingStatus.PAYING);

    // when
    int updated = bookings.confirmPaying(bookingId, LATER);

    // then
    assertThat(updated).isEqualTo(1);
    assertThat(bookingTable.status(bookingId)).isEqualTo("CONFIRMED");
    assertThat(bookingTable.cancelReason(bookingId)).isNull();
  }

  @ParameterizedTest
  @EnumSource(
      value = BookingStatus.class,
      names = {"HELD", "CONFIRMED", "CANCELLED"})
  void shouldNotConfirmBookingWhenItIsNotPaying(BookingStatus status) {
    // given
    UUID bookingId = insert(status);

    // when
    int updated = bookings.confirmPaying(bookingId, LATER);

    // then
    assertThat(updated).isZero();
    assertThat(bookingTable.status(bookingId)).isEqualTo(status.name());
  }

  @Test
  void shouldCancelBookingWithDeclinedReasonWhenItIsPaying() {
    // given
    UUID bookingId = insert(BookingStatus.PAYING);

    // when
    int updated = bookings.declinePaying(bookingId, LATER);

    // then
    assertThat(updated).isEqualTo(1);
    assertThat(bookingTable.status(bookingId)).isEqualTo("CANCELLED");
    assertThat(bookingTable.cancelReason(bookingId)).isEqualTo("PAYMENT_DECLINED");
  }

  @ParameterizedTest
  @EnumSource(
      value = BookingStatus.class,
      names = {"HELD", "CONFIRMED", "CANCELLED"})
  void shouldNotDeclineBookingWhenItIsNotPaying(BookingStatus status) {
    // given
    UUID bookingId = insert(status);

    // when
    int updated = bookings.declinePaying(bookingId, LATER);

    // then
    assertThat(updated).isZero();
    assertThat(bookingTable.status(bookingId)).isEqualTo(status.name());
  }

  @Test
  void shouldCancelConfirmedBookingWhenGuestDropsAPaidStay() {
    // given
    UUID bookingId = insert(BookingStatus.CONFIRMED);

    // when
    int updated = bookings.cancelConfirmed(bookingId, LATER);

    // then
    assertThat(updated).isEqualTo(1);
    assertThat(bookingTable.status(bookingId)).isEqualTo("CANCELLED");
    assertThat(bookingTable.cancelReason(bookingId)).isEqualTo("USER_CANCELLED");
  }

  @ParameterizedTest
  @EnumSource(
      value = BookingStatus.class,
      names = {"HELD", "PAYING", "CANCELLED"})
  void shouldNotCancelConfirmedBookingWhenItIsNotConfirmed(BookingStatus status) {
    // given
    UUID bookingId = insert(status);

    // when
    int updated = bookings.cancelConfirmed(bookingId, LATER);

    // then
    assertThat(updated).isZero();
    assertThat(bookingTable.status(bookingId)).isEqualTo(status.name());
  }

  @Test
  void shouldLeaseAndRestampPayingBookingWhenItsChargeIsOlderThanTheAge() {
    // given
    UUID bookingId = insert(BookingStatus.PAYING);
    bookingTable.setPaymentStartedAt(bookingId, NOW);

    // when
    List<UUID> leased = bookings.leaseStalePayments(LATER, NOW, BATCH);

    // then
    assertThat(leased).containsExactly(bookingId);
    assertThat(bookingTable.paymentStartedAt(bookingId)).contains(LATER);
  }

  /** The lease is what keeps a second sweep off the row until the stale age has passed again. */
  @Test
  void shouldLeaseNothingWhenASweepHasJustTakenTheBooking() {
    // given
    UUID bookingId = insert(BookingStatus.PAYING);
    bookingTable.setPaymentStartedAt(bookingId, NOW);
    bookings.leaseStalePayments(LATER, NOW, BATCH);

    // when
    List<UUID> again = bookings.leaseStalePayments(LATER, NOW, BATCH);

    // then
    assertThat(again).isEmpty();
  }

  @ParameterizedTest
  @EnumSource(
      value = BookingStatus.class,
      names = {"HELD", "CONFIRMED", "CANCELLED"})
  void shouldLeaseNothingWhenBookingIsNotPaying(BookingStatus status) {
    // given
    UUID bookingId = insert(status);

    // when
    List<UUID> leased = bookings.leaseStalePayments(LATER, LATER, BATCH);

    // then
    assertThat(leased).isEmpty();
    assertThat(bookingTable.status(bookingId)).isEqualTo(status.name());
  }

  @Test
  void shouldLeaseNoMoreThanTheBatchWhenSeveralBookingsAreStale() {
    // given
    insert(BookingStatus.PAYING);
    insertInAnotherRoom(BookingStatus.PAYING);

    // when
    List<UUID> leased = bookings.leaseStalePayments(LATER, LATER, 1);

    // then
    assertThat(leased).hasSize(1);
  }

  private UUID insert(BookingStatus status) {
    return bookingTable.insert(aBooking().withRoomId(roomId).withStatus(status));
  }

  private UUID insertInAnotherRoom(BookingStatus status) {
    UUID hotelId = hotels.save(aHotel().withName("Hotel Bristol").build()).getId();
    UUID otherRoomId = rooms.save(aRoom().withHotelId(hotelId).build()).getId();
    return bookingTable.insert(aBooking().withRoomId(otherRoomId).withStatus(status));
  }
}
