package dev.roomlock.booking.payment;

import static dev.roomlock.booking.support.TestData.HOLD_TTL;
import static dev.roomlock.booking.support.TestData.NOW;
import static dev.roomlock.booking.support.TestData.aBooking;
import static dev.roomlock.booking.support.TestData.aHold;
import static dev.roomlock.booking.support.TestData.aHotel;
import static dev.roomlock.booking.support.TestData.aRoom;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.roomlock.booking.domain.Booking;
import dev.roomlock.booking.domain.BookingStatus;
import dev.roomlock.booking.domain.CancelReason;
import dev.roomlock.booking.domain.ResourceNotFoundException;
import dev.roomlock.booking.hold.HoldResult;
import dev.roomlock.booking.hold.HoldService;
import dev.roomlock.booking.infra.HotelRepository;
import dev.roomlock.booking.infra.RoomRepository;
import dev.roomlock.booking.lifecycle.LifecycleService;
import dev.roomlock.booking.support.BookingIntegrationTest;
import dev.roomlock.booking.support.BookingTable;
import dev.roomlock.booking.support.DatabaseCleaner;
import dev.roomlock.booking.support.MutableClock;
import dev.roomlock.booking.support.OutboxTable;
import dev.roomlock.booking.support.StubPaymentGateway;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Paying for a booking: HELD becomes PAYING, the provider decides, and the row plus the outbox end
 * up holding the same story (rules DB-5, K-1).
 */
@BookingIntegrationTest
class PayBookingTest {

  private static final String BOOKING_HELD = "BookingHeld";
  private static final String BOOKING_CONFIRMED = "BookingConfirmed";
  private static final String BOOKING_CANCELLED = "BookingCancelled";
  private static final Duration ONE_MINUTE = Duration.ofMinutes(1);

  /** 450.00 PLN of the default booking, in grosze. */
  private static final long TOTAL_PRICE_MINOR = 45_000;

  @Autowired private PaymentService payments;
  @Autowired private LifecycleService lifecycle;
  @Autowired private HoldService holds;
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
  void shouldConfirmBookingAndRecordItWhenChargeIsApproved() {
    // given
    Booking booking = held();
    clock.advance(ONE_MINUTE);

    // when
    PayResult result = payAsOwner(booking.getId());

    // then
    assertThat(result).isInstanceOf(PayResult.Confirmed.class);
    Booking confirmed = ((PayResult.Confirmed) result).booking();
    assertThat(confirmed.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
    assertThat(confirmed.getCancelReason()).isEmpty();
    assertThat(bookingTable.status(booking.getId())).isEqualTo("CONFIRMED");
    assertThat(bookingTable.paymentStartedAt(booking.getId())).contains(clock.instant());
    assertThat(outbox.typesOf(booking.getId())).containsExactly(BOOKING_HELD, BOOKING_CONFIRMED);
  }

  @Test
  void shouldSendTheTotalPriceInMinorUnitsWhenCharging() {
    // given
    Booking booking = held();

    // when
    payAsOwner(booking.getId());

    // then
    assertThat(provider.received())
        .containsExactly(
            new ChargeOrder(booking.getId(), booking.getGuestId(), TOTAL_PRICE_MINOR, "PLN"));
  }

  @Test
  void shouldCancelBookingWithDeclinedReasonWhenChargeIsDeclined() {
    // given
    Booking booking = held();
    provider.declineEverything("AMOUNT_ABOVE_LIMIT");
    clock.advance(ONE_MINUTE);

    // when
    PayResult result = payAsOwner(booking.getId());

    // then
    assertThat(result).isEqualTo(new PayResult.Declined(booking.getId(), "AMOUNT_ABOVE_LIMIT"));
    assertThat(bookingTable.status(booking.getId())).isEqualTo("CANCELLED");
    assertThat(bookingTable.cancelReason(booking.getId()))
        .isEqualTo(CancelReason.PAYMENT_DECLINED.name());
    assertThat(outbox.typesOf(booking.getId())).containsExactly(BOOKING_HELD, BOOKING_CANCELLED);
  }

  @Test
  void shouldWritePaymentDeclinedReasonOfTheContractWhenChargeIsDeclined() throws Exception {
    // given
    Booking booking = held();
    provider.declineEverything("INSUFFICIENT_FUNDS");

    // when
    payAsOwner(booking.getId());

    // then
    JsonNode event = json.readTree(outbox.payloadOfLast(booking.getId(), BOOKING_CANCELLED));
    assertThat(event.path("bookingId").asText()).isEqualTo(booking.getId().toString());
    assertThat(event.path("reason").asText()).isEqualTo("PAYMENT_DECLINED");
  }

  @Test
  void shouldChargeOnceAndAnswerTheSameWayWhenBookingIsPaidForTwice() {
    // given
    Booking booking = held();
    PayResult first = payAsOwner(booking.getId());

    // when
    PayResult second = payAsOwner(booking.getId());

    // then
    assertThat(first).isInstanceOf(PayResult.Confirmed.class);
    assertThat(second).isInstanceOf(PayResult.AlreadyConfirmed.class);
    assertThat(((PayResult.AlreadyConfirmed) second).booking().getStatus())
        .isEqualTo(BookingStatus.CONFIRMED);
    assertThat(provider.received()).hasSize(1);
    assertThat(outbox.typesOf(booking.getId())).containsExactly(BOOKING_HELD, BOOKING_CONFIRMED);
  }

  @Test
  void shouldRefusePaymentWhenBookingIsCancelled() {
    // given
    Booking booking = held();
    lifecycle.cancel(booking.getId(), booking.getGuestId());

    // when
    PayResult result = payAsOwner(booking.getId());

    // then
    assertThat(result)
        .isEqualTo(new PayResult.NotPayable(booking.getId(), BookingStatus.CANCELLED));
    assertThat(provider.received()).isEmpty();
    assertThat(bookingTable.status(booking.getId())).isEqualTo("CANCELLED");
  }

  @Test
  void shouldRefusePaymentWhenConfirmedBookingWasCancelledAfterwards() {
    // given
    Booking booking = held();
    payAsOwner(booking.getId());
    lifecycle.cancel(booking.getId(), booking.getGuestId());
    provider.reset();

    // when
    PayResult result = payAsOwner(booking.getId());

    // then
    assertThat(result)
        .isEqualTo(new PayResult.NotPayable(booking.getId(), BookingStatus.CANCELLED));
    assertThat(provider.received()).isEmpty();
  }

  @Test
  void shouldRefusePaymentWhenAnEarlierChargeWasDeclined() {
    // given
    Booking booking = held();
    provider.declineEverything("INSUFFICIENT_FUNDS");
    payAsOwner(booking.getId());
    provider.reset();

    // when
    PayResult result = payAsOwner(booking.getId());

    // then
    assertThat(result)
        .isEqualTo(new PayResult.NotPayable(booking.getId(), BookingStatus.CANCELLED));
    assertThat(provider.received()).isEmpty();
  }

  @Test
  void shouldRefusePaymentWhenHoldHasExpired() {
    // given
    Booking booking = held();
    clock.advance(HOLD_TTL.plus(ONE_MINUTE));
    lifecycle.expireHolds();

    // when
    PayResult result = payAsOwner(booking.getId());

    // then
    assertThat(result)
        .isEqualTo(new PayResult.NotPayable(booking.getId(), BookingStatus.CANCELLED));
    assertThat(provider.received()).isEmpty();
  }

  @Test
  void shouldLeaveBookingPayingWithoutAnEventWhenProviderGivesNoAnswer() {
    // given
    Booking booking = held();
    provider.beUnavailable();
    clock.advance(ONE_MINUTE);

    // when
    PayResult result = payAsOwner(booking.getId());

    // then
    assertThat(result).isEqualTo(new PayResult.ProviderUnavailable(booking.getId()));
    assertThat(bookingTable.status(booking.getId())).isEqualTo("PAYING");
    assertThat(bookingTable.paymentStartedAt(booking.getId())).contains(clock.instant());
    assertThat(bookingTable.cancelReason(booking.getId())).isNull();
    assertThat(outbox.typesOf(booking.getId())).containsExactly(BOOKING_HELD);
  }

  @Test
  void shouldAskTheProviderAgainWhenAPayingBookingIsPaidForAgain() {
    // given
    Booking booking = held();
    provider.beUnavailable();
    payAsOwner(booking.getId());
    provider.approveEverything();

    // when
    PayResult result = payAsOwner(booking.getId());

    // then
    assertThat(result).isInstanceOf(PayResult.Confirmed.class);
    assertThat(provider.received()).hasSize(2);
    assertThat(bookingTable.status(booking.getId())).isEqualTo("CONFIRMED");
    assertThat(outbox.typesOf(booking.getId())).containsExactly(BOOKING_HELD, BOOKING_CONFIRMED);
  }

  @Test
  void shouldFailWithNotFoundWhenBookingIsUnknown() {
    // given
    UUID unknownId = UUID.randomUUID();

    // when
    // then
    assertThatExceptionOfType(ResourceNotFoundException.class)
        .isThrownBy(() -> payments.pay(unknownId, UUID.randomUUID()))
        .satisfies(
            thrown -> {
              assertThat(thrown.resource()).isEqualTo("booking");
              assertThat(thrown.id()).isEqualTo(unknownId);
            });
    assertThat(provider.received()).isEmpty();
  }

  @Test
  void shouldKeepRoomBusyForAnotherGuestWhenBookingIsConfirmed() {
    // given
    Booking booking = held();
    payAsOwner(booking.getId());

    // when
    HoldResult result = holds.hold(aHold().withRoomId(roomId).build());

    // then
    assertThat(result).isInstanceOf(HoldResult.RoomBusy.class);
    assertThat(bookingTable.status(booking.getId())).isEqualTo("CONFIRMED");
  }

  /** A PAYING row written straight to the table, as a crashed request would have left it. */
  @Test
  void shouldConfirmBookingWhenItWasLeftPayingByAnEarlierRequest() {
    // given
    UUID bookingId =
        bookingTable.insert(aBooking().withRoomId(roomId).withStatus(BookingStatus.PAYING));

    // when
    PayResult result = payAsOwner(bookingId);

    // then
    assertThat(result).isInstanceOf(PayResult.Confirmed.class);
    assertThat(bookingTable.status(bookingId)).isEqualTo("CONFIRMED");
    assertThat(outbox.typesOf(bookingId)).containsExactly(BOOKING_CONFIRMED);
  }

  /**
   * The pay of the guest the booking belongs to. Every booking of this class has one owner, and
   * paying for somebody else's is not what these tests are about - ApiSecurityIntegrationTest and
   * BookingOwnershipIntegrationTest cover that.
   */
  private PayResult payAsOwner(UUID bookingId) {
    return payments.pay(bookingId, bookingTable.guestId(bookingId));
  }

  private Booking held() {
    return ((HoldResult.Held) holds.hold(aHold().withRoomId(roomId).build())).booking();
  }

  private UUID newRoom() {
    UUID hotelId = hotels.save(aHotel().build()).getId();
    return rooms.save(aRoom().withHotelId(hotelId).build()).getId();
  }
}
