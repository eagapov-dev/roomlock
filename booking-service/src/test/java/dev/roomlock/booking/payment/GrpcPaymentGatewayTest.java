package dev.roomlock.booking.payment;

import static org.assertj.core.api.Assertions.assertThat;

import dev.roomlock.booking.support.StubPaymentServer;
import dev.roomlock.booking.support.StubPaymentServer.Answer;
import dev.roomlock.payment.api.v1.ChargeRequest;
import dev.roomlock.payment.api.v1.ChargeResponse;
import io.grpc.Status;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The gRPC adapter against a real server serving the contract of payment-api (rule T-4): what it
 * sends, how it reads every answer, and what a call that never comes back turns into.
 */
class GrpcPaymentGatewayTest {

  private static final Duration DEADLINE = Duration.ofMillis(300);
  private static final String CURRENCY = "PLN";
  private static final long AMOUNT_MINOR = 45_000;

  @Test
  void shouldSendTheOrderAsTheContractDescribesItWhenCharging() {
    // given
    ChargeOrder order = anOrder();

    try (StubPaymentServer provider = StubPaymentServer.approving()) {
      // when
      gatewayOf(provider).charge(order);

      // then
      assertThat(provider.received()).hasSize(1);
      ChargeRequest sent = provider.received().getFirst();
      assertThat(sent.getBookingId()).isEqualTo(order.bookingId().toString());
      assertThat(sent.getGuestId()).isEqualTo(order.guestId().toString());
      assertThat(sent.getAmountMinor()).isEqualTo(AMOUNT_MINOR);
      assertThat(sent.getCurrency()).isEqualTo(CURRENCY);
    }
  }

  @Test
  void shouldReportApprovedWithThePaymentIdWhenProviderApproves() {
    // given
    ChargeResponse response = StubPaymentServer.approved();

    try (StubPaymentServer provider = StubPaymentServer.answering(new Answer.Respond(response))) {
      // when
      ChargeOutcome outcome = gatewayOf(provider).charge(anOrder());

      // then
      assertThat(outcome).isEqualTo(new ChargeOutcome.Approved(response.getPaymentId()));
    }
  }

  @Test
  void shouldReportDeclinedWithTheReasonWhenProviderDeclines() {
    // given
    ChargeResponse response = StubPaymentServer.declined("AMOUNT_ABOVE_LIMIT");

    try (StubPaymentServer provider = StubPaymentServer.answering(new Answer.Respond(response))) {
      // when
      ChargeOutcome outcome = gatewayOf(provider).charge(anOrder());

      // then
      assertThat(outcome).isEqualTo(new ChargeOutcome.Declined("AMOUNT_ABOVE_LIMIT"));
    }
  }

  @Test
  void shouldReportUnavailableWhenProviderFailsTheCall() {
    // given
    Answer refused = new Answer.Fail(Status.UNAVAILABLE.withDescription("no acquirer"));

    try (StubPaymentServer provider = StubPaymentServer.answering(refused)) {
      // when
      ChargeOutcome outcome = gatewayOf(provider).charge(anOrder());

      // then
      assertThat(outcome).isEqualTo(new ChargeOutcome.Unavailable("UNAVAILABLE"));
    }
  }

  /**
   * A mismatched idempotency key is not a decline: nothing is known about the money, so the booking
   * has to keep waiting rather than be cancelled (rule I-2).
   */
  @Test
  void shouldReportUnavailableWhenProviderRefusesTheRequestItself() {
    // given
    Answer refused = new Answer.Fail(Status.FAILED_PRECONDITION);

    try (StubPaymentServer provider = StubPaymentServer.answering(refused)) {
      // when
      ChargeOutcome outcome = gatewayOf(provider).charge(anOrder());

      // then
      assertThat(outcome).isEqualTo(new ChargeOutcome.Unavailable("FAILED_PRECONDITION"));
    }
  }

  /** The deadline, not the server, ends the call: nothing here sleeps (rule T-7). */
  @Test
  void shouldReportUnavailableWhenDeadlineExpiresBeforeTheAnswer() {
    // given
    try (StubPaymentServer provider = StubPaymentServer.answering(new Answer.Hang())) {
      // when
      ChargeOutcome outcome = gatewayOf(provider).charge(anOrder());

      // then
      assertThat(outcome).isEqualTo(new ChargeOutcome.Unavailable("DEADLINE_EXCEEDED"));
      assertThat(provider.received()).hasSize(1);
    }
  }

  @Test
  void shouldReportUnavailableWhenProviderIsNotThereAtAll() {
    // given
    StubPaymentServer provider = StubPaymentServer.approving();
    PaymentGateway gateway = gatewayOf(provider);
    provider.close();

    // when
    ChargeOutcome outcome = gateway.charge(anOrder());

    // then
    assertThat(outcome).isInstanceOf(ChargeOutcome.Unavailable.class);
  }

  private static GrpcPaymentGateway gatewayOf(StubPaymentServer provider) {
    return new GrpcPaymentGateway(provider.stub(), propertiesWith(DEADLINE));
  }

  private static PaymentProperties propertiesWith(Duration deadline) {
    return new PaymentProperties(
        deadline,
        new PaymentProperties.Reconcile(Duration.ofSeconds(10), Duration.ofMinutes(1), 1));
  }

  private static ChargeOrder anOrder() {
    return new ChargeOrder(UUID.randomUUID(), UUID.randomUUID(), AMOUNT_MINOR, CURRENCY);
  }
}
