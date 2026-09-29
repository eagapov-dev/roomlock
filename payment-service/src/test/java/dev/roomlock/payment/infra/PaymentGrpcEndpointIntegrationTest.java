package dev.roomlock.payment.infra;

import static dev.roomlock.payment.support.TestData.AMOUNT_MINOR;
import static dev.roomlock.payment.support.TestData.AMOUNT_MINOR_ABOVE_LIMIT;
import static dev.roomlock.payment.support.TestData.CURRENCY;
import static dev.roomlock.payment.support.TestData.NOW;
import static dev.roomlock.payment.support.TestData.aCharge;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import dev.roomlock.payment.api.v1.ChargeRequest;
import dev.roomlock.payment.api.v1.ChargeResponse;
import dev.roomlock.payment.api.v1.RefundResponse;
import dev.roomlock.payment.domain.PaymentStatus;
import dev.roomlock.payment.support.DatabaseCleaner;
import dev.roomlock.payment.support.MutableClock;
import dev.roomlock.payment.support.PaymentGrpcClient;
import dev.roomlock.payment.support.PaymentIntegrationTest;
import dev.roomlock.payment.support.PaymentTable;
import dev.roomlock.payment.support.PaymentTable.Row;
import dev.roomlock.payment.support.TestData.ChargeDraft;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The gRPC contract of payment-api over a real channel: what a charge decides, what a repeated call
 * gets, and what a refund does in every state a payment can be in.
 */
@PaymentIntegrationTest
class PaymentGrpcEndpointIntegrationTest {

  @Autowired private PaymentGrpcClient grpc;
  @Autowired private PaymentTable payments;
  @Autowired private DatabaseCleaner databaseCleaner;
  @Autowired private MutableClock clock;

  @BeforeEach
  void setUp() {
    databaseCleaner.truncateAll();
    clock.setInstant(NOW);
  }

  @Test
  void shouldApproveAndRecordTheChargeWhenAmountIsWithinTheLimit() {
    // given
    ChargeDraft draft = aCharge();

    // when
    ChargeResponse response = grpc.charge(draft.build());

    // then
    assertThat(response.getStatus()).isEqualTo(ChargeResponse.Status.APPROVED);
    assertThat(response.getDeclineReason()).isEmpty();
    Row recorded = payments.findByBookingId(draft.bookingId()).orElseThrow();
    assertThat(recorded.id()).isEqualTo(UUID.fromString(response.getPaymentId()));
    assertThat(recorded.guestId()).isEqualTo(draft.guestId());
    assertThat(recorded.amountMinor()).isEqualTo(AMOUNT_MINOR);
    assertThat(recorded.currency()).isEqualTo(CURRENCY);
    assertThat(recorded.status()).isEqualTo(PaymentStatus.APPROVED.name());
    assertThat(recorded.declineReason()).isNull();
    assertThat(recorded.chargedAt()).isEqualTo(NOW);
    assertThat(recorded.refundedAt()).isNull();
  }

  @Test
  void shouldDeclineWithAReasonWhenAmountIsAboveTheLimit() {
    // given
    ChargeDraft draft = aCharge().aboveTheLimit();

    // when
    ChargeResponse response = grpc.charge(draft.build());

    // then
    assertThat(response.getStatus()).isEqualTo(ChargeResponse.Status.DECLINED);
    assertThat(response.getDeclineReason())
        .contains(String.valueOf(AMOUNT_MINOR_ABOVE_LIMIT))
        .contains("above the limit");
    Row recorded = payments.findByBookingId(draft.bookingId()).orElseThrow();
    assertThat(recorded.status()).isEqualTo(PaymentStatus.DECLINED.name());
    assertThat(recorded.declineReason()).isEqualTo(response.getDeclineReason());
  }

  @Test
  void shouldAnswerTheFirstDecisionWhenTheSameBookingIsChargedTwice() {
    // given
    ChargeRequest request = aCharge().build();
    ChargeResponse first = grpc.charge(request);

    // when
    ChargeResponse second = grpc.charge(request);

    // then
    assertThat(second).isEqualTo(first);
    assertThat(payments.countForBooking(UUID.fromString(request.getBookingId()))).isEqualTo(1);
  }

  @Test
  void shouldAnswerTheFirstDeclineWhenADeclinedBookingIsChargedTwice() {
    // given
    ChargeRequest request = aCharge().aboveTheLimit().build();
    ChargeResponse first = grpc.charge(request);

    // when
    ChargeResponse second = grpc.charge(request);

    // then
    assertThat(second).isEqualTo(first);
    assertThat(second.getStatus()).isEqualTo(ChargeResponse.Status.DECLINED);
    assertThat(payments.countForBooking(UUID.fromString(request.getBookingId()))).isEqualTo(1);
  }

  @Test
  void shouldRefuseTheChargeWhenTheAmountOfTheSameBookingDiffers() {
    // given
    ChargeDraft draft = aCharge();
    grpc.charge(draft.build());

    // when
    ChargeRequest changed = draft.withAmountMinor(AMOUNT_MINOR + 1).build();

    // then
    assertThatExceptionOfType(StatusRuntimeException.class)
        .isThrownBy(() -> grpc.charge(changed))
        .satisfies(
            failure -> assertThat(statusOf(failure)).isEqualTo(Status.Code.FAILED_PRECONDITION))
        .withMessageContaining("idempotency key");
    Row recorded = payments.findByBookingId(draft.bookingId()).orElseThrow();
    assertThat(recorded.amountMinor()).isEqualTo(AMOUNT_MINOR);
  }

  @Test
  void shouldRefuseTheChargeWhenTheGuestOfTheSameBookingDiffers() {
    // given
    ChargeDraft draft = aCharge();
    grpc.charge(draft.build());

    // when
    ChargeRequest changed = draft.withGuestId(UUID.randomUUID()).build();

    // then
    assertThatExceptionOfType(StatusRuntimeException.class)
        .isThrownBy(() -> grpc.charge(changed))
        .satisfies(
            failure -> assertThat(statusOf(failure)).isEqualTo(Status.Code.FAILED_PRECONDITION));
    Row recorded = payments.findByBookingId(draft.bookingId()).orElseThrow();
    assertThat(recorded.guestId()).isEqualTo(draft.guestId());
  }

  @Test
  void shouldRejectTheChargeWhenTheAmountIsNotPositive() {
    // given
    ChargeRequest request = aCharge().withAmountMinor(0).build();

    // when
    // then
    assertThatExceptionOfType(StatusRuntimeException.class)
        .isThrownBy(() -> grpc.charge(request))
        .satisfies(
            failure -> assertThat(statusOf(failure)).isEqualTo(Status.Code.INVALID_ARGUMENT));
    assertThat(payments.findByBookingId(UUID.fromString(request.getBookingId()))).isEmpty();
  }

  @Test
  void shouldRejectTheRefundWhenTheBookingIdIsNotAnId() {
    // given
    String notAnId = "not-a-uuid";

    // when
    // then
    assertThatExceptionOfType(StatusRuntimeException.class)
        .isThrownBy(() -> grpc.refundRaw(notAnId))
        .satisfies(
            failure -> assertThat(statusOf(failure)).isEqualTo(Status.Code.INVALID_ARGUMENT));
  }

  @Test
  void shouldRefundTheApprovedChargeWhenRefundIsAsked() {
    // given
    ChargeDraft draft = aCharge();
    grpc.charge(draft.build());
    clock.advance(Duration.ofMinutes(5));

    // when
    RefundResponse response = grpc.refund(draft.bookingId());

    // then
    assertThat(response.getRefunded()).isTrue();
    Row recorded = payments.findByBookingId(draft.bookingId()).orElseThrow();
    assertThat(recorded.status()).isEqualTo(PaymentStatus.REFUNDED.name());
    assertThat(recorded.refundedAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
  }

  @Test
  void shouldAnswerRefundedWithoutRefundingAgainWhenTheBookingIsAlreadyRefunded() {
    // given
    ChargeDraft draft = aCharge();
    grpc.charge(draft.build());
    grpc.refund(draft.bookingId());
    clock.advance(Duration.ofHours(1));

    // when
    RefundResponse response = grpc.refund(draft.bookingId());

    // then
    assertThat(response.getRefunded()).isTrue();
    Row recorded = payments.findByBookingId(draft.bookingId()).orElseThrow();
    assertThat(recorded.refundedAt()).isEqualTo(NOW);
  }

  @Test
  void shouldStillAnswerApprovedWhenARefundedBookingIsChargedAgain() {
    // given
    ChargeRequest request = aCharge().build();
    ChargeResponse charged = grpc.charge(request);
    grpc.refund(UUID.fromString(request.getBookingId()));

    // when
    ChargeResponse replay = grpc.charge(request);

    // then
    assertThat(replay).isEqualTo(charged);
    assertThat(replay.getStatus()).isEqualTo(ChargeResponse.Status.APPROVED);
  }

  @Test
  void shouldNotRefundWhenTheBookingWasNeverCharged() {
    // given
    UUID unknownBooking = UUID.randomUUID();

    // when
    RefundResponse response = grpc.refund(unknownBooking);

    // then
    assertThat(response.getRefunded()).isFalse();
    assertThat(payments.findByBookingId(unknownBooking)).isEmpty();
  }

  @Test
  void shouldNotRefundWhenTheChargeWasDeclined() {
    // given
    ChargeDraft draft = aCharge().aboveTheLimit();
    grpc.charge(draft.build());

    // when
    RefundResponse response = grpc.refund(draft.bookingId());

    // then
    assertThat(response.getRefunded()).isFalse();
    Row recorded = payments.findByBookingId(draft.bookingId()).orElseThrow();
    assertThat(recorded.status()).isEqualTo(PaymentStatus.DECLINED.name());
    assertThat(recorded.refundedAt()).isNull();
  }

  private static Status.Code statusOf(StatusRuntimeException failure) {
    return failure.getStatus().getCode();
  }
}
