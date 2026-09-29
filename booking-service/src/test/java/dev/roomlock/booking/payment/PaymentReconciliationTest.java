package dev.roomlock.booking.payment;

import static dev.roomlock.booking.support.TestData.NOW;
import static dev.roomlock.booking.support.TestData.aBooking;
import static dev.roomlock.booking.support.TestData.aHotel;
import static dev.roomlock.booking.support.TestData.aRoom;
import static dev.roomlock.booking.support.TestData.november;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.roomlock.booking.domain.BookingStatus;
import dev.roomlock.booking.infra.HotelRepository;
import dev.roomlock.booking.infra.RoomRepository;
import dev.roomlock.booking.support.BookingIntegrationTest;
import dev.roomlock.booking.support.BookingTable;
import dev.roomlock.booking.support.DatabaseCleaner;
import dev.roomlock.booking.support.MutableClock;
import dev.roomlock.booking.support.OutboxTable;
import dev.roomlock.booking.support.StubPaymentGateway;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Reconciliation of bookings left PAYING: the sweep asks the provider again and applies the answer
 * it gets, with the same transitions and the same events a request would write. The clock is moved
 * instead of waiting (rule T-7).
 */
@BookingIntegrationTest
class PaymentReconciliationTest {

  private static final String BOOKING_CONFIRMED = "BookingConfirmed";
  private static final String BOOKING_CANCELLED = "BookingCancelled";

  /** Past {@code roomlock.payment.reconcile.after}, which is one minute. */
  private static final Duration STALE = Duration.ofMinutes(2);

  private static final Duration FRESH = Duration.ofSeconds(1);

  @Autowired private PaymentReconciler reconciler;
  @Autowired private StubPaymentGateway provider;
  @Autowired private BookingTable bookingTable;
  @Autowired private OutboxTable outbox;
  @Autowired private ObjectMapper json;
  @Autowired private HotelRepository hotels;
  @Autowired private RoomRepository rooms;
  @Autowired private MutableClock clock;
  @Autowired private DatabaseCleaner databaseCleaner;

  private UUID roomId;

  @BeforeEach
  void setUp() {
    databaseCleaner.truncateAll();
    clock.setInstant(NOW);
    provider.reset();
    roomId = newRoom();
  }

  @Test
  void shouldConfirmStaleBookingWhenProviderApprovesTheChargeItNeverAnsweredFor() {
    // given
    UUID bookingId = paying();
    clock.advance(STALE);

    // when
    int settled = reconciler.reconcileBatch();

    // then
    assertThat(settled).isEqualTo(1);
    assertThat(bookingTable.status(bookingId)).isEqualTo("CONFIRMED");
    assertThat(outbox.typesOf(bookingId)).containsExactly(BOOKING_CONFIRMED);
    assertThat(provider.received()).hasSize(1);
    assertThat(provider.received().getFirst().bookingId()).isEqualTo(bookingId);
  }

  @Test
  void shouldCancelStaleBookingWithDeclinedReasonWhenProviderDeclines() throws Exception {
    // given
    UUID bookingId = paying();
    provider.declineEverything("INSUFFICIENT_FUNDS");
    clock.advance(STALE);

    // when
    int settled = reconciler.reconcileBatch();

    // then
    assertThat(settled).isEqualTo(1);
    assertThat(bookingTable.status(bookingId)).isEqualTo("CANCELLED");
    assertThat(bookingTable.cancelReason(bookingId)).isEqualTo("PAYMENT_DECLINED");
    assertThat(outbox.typesOf(bookingId)).containsExactly(BOOKING_CANCELLED);
    JsonNode event = json.readTree(outbox.payloadOfLast(bookingId, BOOKING_CANCELLED));
    assertThat(event.path("reason").asText()).isEqualTo("PAYMENT_DECLINED");
  }

  @Test
  void shouldLeaveFreshPayingBookingAloneWhenItsRequestMayStillBeWaiting() {
    // given
    UUID bookingId = paying();
    clock.advance(FRESH);

    // when
    int settled = reconciler.reconcileBatch();

    // then
    assertThat(settled).isZero();
    assertThat(bookingTable.status(bookingId)).isEqualTo("PAYING");
    assertThat(provider.received()).isEmpty();
    assertThat(outbox.count()).isZero();
  }

  @Test
  void shouldLeaveBookingPayingWithoutAnEventWhenProviderStillGivesNoAnswer() {
    // given
    UUID bookingId = paying();
    provider.beUnavailable();
    clock.advance(STALE);

    // when
    int settled = reconciler.reconcileBatch();

    // then
    assertThat(settled).isZero();
    assertThat(bookingTable.status(bookingId)).isEqualTo("PAYING");
    assertThat(outbox.count()).isZero();
    assertThat(provider.received()).hasSize(1);
  }

  /** The lease re-stamps the row, so the next sweep waits the stale age out again. */
  @Test
  void shouldNotChargeAgainImmediatelyWhenAPreviousSweepJustTookTheBooking() {
    // given
    paying();
    provider.beUnavailable();
    clock.advance(STALE);
    reconciler.reconcileBatch();

    // when
    int settled = reconciler.reconcileBatch();

    // then
    assertThat(settled).isZero();
    assertThat(provider.received()).hasSize(1);
  }

  @Test
  void shouldChargeAgainWhenTheStaleAgeHasPassedOnceMore() {
    // given
    paying();
    provider.beUnavailable();
    clock.advance(STALE);
    reconciler.reconcileBatch();
    provider.approveEverything();
    clock.advance(STALE);

    // when
    int settled = reconciler.reconcileBatch();

    // then
    assertThat(settled).isEqualTo(1);
    assertThat(provider.received()).hasSize(2);
  }

  @Test
  void shouldIgnoreBookingsThatAreNotPayingWhenSweeping() {
    // given
    UUID held = insert(BookingStatus.HELD, november(10), november(13));
    UUID confirmed = insertInAnotherRoom(BookingStatus.CONFIRMED);
    clock.advance(STALE);

    // when
    int settled = reconciler.reconcileBatch();

    // then
    assertThat(settled).isZero();
    assertThat(provider.received()).isEmpty();
    assertThat(bookingTable.status(held)).isEqualTo("HELD");
    assertThat(bookingTable.status(confirmed)).isEqualTo("CONFIRMED");
  }

  @Test
  void shouldSettleEveryStaleBookingWhenSeveralWereLeftPaying() {
    // given
    UUID first = paying();
    UUID second = insertInAnotherRoom(BookingStatus.PAYING);
    clock.advance(STALE);

    // when
    int settled = reconciler.reconcileBatch();

    // then
    assertThat(settled).isEqualTo(2);
    assertThat(bookingTable.status(first)).isEqualTo("CONFIRMED");
    assertThat(bookingTable.status(second)).isEqualTo("CONFIRMED");
    assertThat(outbox.typesOf(first)).containsExactly(BOOKING_CONFIRMED);
    assertThat(outbox.typesOf(second)).containsExactly(BOOKING_CONFIRMED);
  }

  private UUID paying() {
    return insert(BookingStatus.PAYING, november(10), november(13));
  }

  private UUID insert(BookingStatus status, LocalDate checkIn, LocalDate checkOut) {
    return bookingTable.insert(
        aBooking().withRoomId(roomId).withStay(checkIn, checkOut).withStatus(status));
  }

  private UUID insertInAnotherRoom(BookingStatus status) {
    UUID hotelId = hotels.save(aHotel().withName("Hotel Bristol").build()).getId();
    UUID otherRoomId = rooms.save(aRoom().withHotelId(hotelId).build()).getId();
    return bookingTable.insert(aBooking().withRoomId(otherRoomId).withStatus(status));
  }

  private UUID newRoom() {
    UUID hotelId = hotels.save(aHotel().build()).getId();
    return rooms.save(aRoom().withHotelId(hotelId).build()).getId();
  }
}
