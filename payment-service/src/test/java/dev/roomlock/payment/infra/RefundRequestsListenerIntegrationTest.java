package dev.roomlock.payment.infra;

import static dev.roomlock.payment.support.TestData.NOW;
import static dev.roomlock.payment.support.TestData.aRefundRequested;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.roomlock.contract.v1.RefundRequested;
import dev.roomlock.payment.domain.PaymentStatus;
import dev.roomlock.payment.support.DatabaseCleaner;
import dev.roomlock.payment.support.MutableClock;
import dev.roomlock.payment.support.PaymentGrpcClient;
import dev.roomlock.payment.support.PaymentIntegrationTest;
import dev.roomlock.payment.support.PaymentTable;
import dev.roomlock.payment.support.PaymentTable.Row;
import dev.roomlock.payment.support.RefundRequestPublisher;
import dev.roomlock.payment.support.TestData.RefundDraft;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The consumer of {@code roomlock.refund-requests.v1}: an event refunds the charge through the same
 * use case the gRPC call uses, a redelivery refunds nothing more, and a record the consumer cannot
 * read does not stop it.
 *
 * <p>Waiting on the broker is {@link org.awaitility.Awaitility}, never a sleep (rule T-7). Where a
 * test has to know that a record was already consumed, it publishes a marker event under the same
 * key: the same key means the same partition, so the marker is handled last.
 */
@PaymentIntegrationTest
class RefundRequestsListenerIntegrationTest {

  private static final Duration CONSUMED = Duration.ofSeconds(30);

  @Autowired private RefundRequestPublisher events;
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
  void shouldRefundTheChargeWhenRefundWasRequested() {
    // given
    RefundDraft draft = aRefundRequested();
    grpc.charge(draft.itsCharge().build());
    clock.advance(Duration.ofMinutes(30));

    // when
    events.publish(draft.build());

    // then
    Row refunded = awaitRefunded(draft.bookingId());
    assertThat(refunded.status()).isEqualTo(PaymentStatus.REFUNDED.name());
    assertThat(refunded.refundedAt()).isEqualTo(NOW.plus(Duration.ofMinutes(30)));
  }

  @Test
  void shouldRefundOnceWhenTheSameEventIsDeliveredTwice() {
    // given
    RefundDraft draft = aRefundRequested();
    grpc.charge(draft.itsCharge().build());
    RefundRequested event = draft.build();

    // when
    events.publish(event);
    awaitRefunded(draft.bookingId());
    clock.advance(Duration.ofHours(2));
    events.publish(event);

    // then
    awaitRefunded(markerAfter(draft).bookingId());
    Row refunded = payments.findByBookingId(draft.bookingId()).orElseThrow();
    assertThat(refunded.refundedAt()).isEqualTo(NOW);
  }

  @Test
  void shouldKeepConsumingWhenPayloadIsMalformed() {
    // given
    RefundDraft draft = aRefundRequested();
    grpc.charge(draft.itsCharge().build());

    // when
    events.publishRaw(draft.bookingId().toString(), "{\"eventId\": not json", "RefundRequested");
    events.publish(draft.build());

    // then
    Row refunded = awaitRefunded(draft.bookingId());
    assertThat(refunded.status()).isEqualTo(PaymentStatus.REFUNDED.name());
  }

  @Test
  void shouldKeepConsumingWhenEventTypeIsUnknown() {
    // given
    RefundDraft draft = aRefundRequested();
    grpc.charge(draft.itsCharge().build());
    String payload = "{\"eventId\":\"" + UUID.randomUUID() + "\"}";

    // when
    events.publishRaw(draft.bookingId().toString(), payload, "BookingRescheduled");
    events.publish(draft.build());

    // then
    assertThat(awaitRefunded(draft.bookingId()).status()).isEqualTo(PaymentStatus.REFUNDED.name());
  }

  @Test
  void shouldKeepConsumingWhenEventTypeHeaderIsMissing() {
    // given
    RefundDraft draft = aRefundRequested();
    grpc.charge(draft.itsCharge().build());
    String payload = "{\"eventId\":\"" + UUID.randomUUID() + "\"}";

    // when
    events.publishRaw(draft.bookingId().toString(), payload, null);
    events.publish(draft.build());

    // then
    assertThat(awaitRefunded(draft.bookingId()).status()).isEqualTo(PaymentStatus.REFUNDED.name());
  }

  @Test
  void shouldKeepConsumingWhenTheBookingWasNeverCharged() {
    // given
    RefundDraft unknown = aRefundRequested();
    RefundDraft charged = aRefundRequested();
    grpc.charge(charged.itsCharge().build());

    // when
    events.publishAs(unknown.bookingId().toString(), unknown.build());
    events.publishAs(unknown.bookingId().toString(), charged.build());

    // then
    assertThat(awaitRefunded(charged.bookingId()).status())
        .isEqualTo(PaymentStatus.REFUNDED.name());
    assertThat(payments.findByBookingId(unknown.bookingId())).isEmpty();
  }

  /**
   * A refund of another, already charged booking published under the key of {@code draft}: it lands
   * on the same partition, so once it is refunded every earlier record of that key was handled.
   */
  private RefundDraft markerAfter(RefundDraft draft) {
    RefundDraft marker = aRefundRequested();
    grpc.charge(marker.itsCharge().build());
    events.publishAs(draft.bookingId().toString(), marker.build());
    return marker;
  }

  private Row awaitRefunded(UUID bookingId) {
    await()
        .atMost(CONSUMED)
        .untilAsserted(
            () ->
                assertThat(payments.findByBookingId(bookingId))
                    .get()
                    .extracting(Row::refundedAt)
                    .isNotNull());
    return payments.findByBookingId(bookingId).orElseThrow();
  }
}
