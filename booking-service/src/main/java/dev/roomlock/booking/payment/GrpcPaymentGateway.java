package dev.roomlock.booking.payment;

import dev.roomlock.payment.api.v1.ChargeRequest;
import dev.roomlock.payment.api.v1.ChargeResponse;
import dev.roomlock.payment.api.v1.PaymentServiceGrpc.PaymentServiceBlockingStub;
import io.grpc.StatusRuntimeException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The {@link PaymentGateway} over the {@code roomlock.payment.v1.PaymentService} contract of
 * payment-api.
 *
 * <p>Only mapping happens here (rule A-2): an order becomes a request, a response becomes an
 * outcome, and no decision is taken.
 *
 * <p>Every call carries a deadline, so a provider that stops answering cannot pin a request thread
 * for as long as the client keeps the connection. A deadline that expires and a call that fails are
 * the same outcome for the flow above - {@link ChargeOutcome.Unavailable}, never an exception that
 * escapes as a 500 (rule E-2) - because in both cases nothing is known about the money and the only
 * safe move is to ask again.
 *
 * <p>{@code INVALID_ARGUMENT} and {@code FAILED_PRECONDITION}, which payment-service uses for a
 * malformed request and for a booking id charged with a different amount (rule I-2), are
 * Unavailable too, deliberately: neither can be repaired by this service, and asking again every
 * sweep keeps the booking visible in the log until someone looks at it, which is better than
 * silently cancelling a stay over a bug on this side.
 */
@Component
class GrpcPaymentGateway implements PaymentGateway {

  private static final Logger log = LoggerFactory.getLogger(GrpcPaymentGateway.class);

  private final PaymentServiceBlockingStub payments;
  private final Duration deadline;

  GrpcPaymentGateway(PaymentServiceBlockingStub payments, PaymentProperties properties) {
    this.payments = payments;
    this.deadline = properties.deadline();
  }

  @Override
  public ChargeOutcome charge(ChargeOrder order) {
    try {
      return outcomeOf(payments.withDeadlineAfter(deadline).charge(requestOf(order)));
    } catch (StatusRuntimeException noAnswer) {
      log.warn(
          "Charge of booking {} got no answer: {}",
          order.bookingId(),
          noAnswer.getStatus(),
          noAnswer);
      return new ChargeOutcome.Unavailable(noAnswer.getStatus().getCode().name());
    }
  }

  private static ChargeRequest requestOf(ChargeOrder order) {
    return ChargeRequest.newBuilder()
        .setBookingId(order.bookingId().toString())
        .setGuestId(order.guestId().toString())
        .setAmountMinor(order.amountMinor())
        .setCurrency(order.currency())
        .build();
  }

  /**
   * {@code STATUS_UNSPECIFIED} cannot come from this contract's service, but proto3 lets any peer
   * send it: treating an answer nobody can read as no answer keeps a booking waiting instead of
   * confirming or cancelling a stay on a guess.
   */
  private static ChargeOutcome outcomeOf(ChargeResponse response) {
    return switch (response.getStatus()) {
      case APPROVED -> new ChargeOutcome.Approved(response.getPaymentId());
      case DECLINED -> new ChargeOutcome.Declined(response.getDeclineReason());
      case STATUS_UNSPECIFIED, UNRECOGNIZED ->
          new ChargeOutcome.Unavailable("Charge answered " + response.getStatus());
    };
  }
}
