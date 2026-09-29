package dev.roomlock.payment.infra;

import dev.roomlock.payment.api.v1.ChargeRequest;
import dev.roomlock.payment.api.v1.ChargeResponse;
import dev.roomlock.payment.api.v1.PaymentServiceGrpc;
import dev.roomlock.payment.api.v1.RefundRequest;
import dev.roomlock.payment.api.v1.RefundResponse;
import dev.roomlock.payment.domain.Payment;
import dev.roomlock.payment.domain.PaymentStatus;
import dev.roomlock.payment.payments.ChargeCommand;
import dev.roomlock.payment.payments.ChargeMismatch;
import dev.roomlock.payment.payments.ChargeResult;
import dev.roomlock.payment.payments.PaymentService;
import dev.roomlock.payment.payments.RefundCommand;
import dev.roomlock.payment.payments.RefundResult;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Locale;
import java.util.UUID;
import org.springframework.grpc.server.service.GrpcService;

/**
 * The {@code roomlock.payment.v1.PaymentService} contract of payment-api.
 *
 * <p>Only mapping happens here (rule A-2): a request becomes a command, every outcome of {@link
 * ChargeResult} and {@link RefundResult} becomes a response or a status, and no decision is taken.
 *
 * <p>The statuses, none of which is a business outcome:
 *
 * <ul>
 *   <li>{@code INVALID_ARGUMENT} - the request is not a request at all: an id that is not a UUID,
 *       an amount that is not positive, a currency that is not three letters. It says nothing about
 *       the state of the service, so the caller has to fix the message.
 *   <li>{@code FAILED_PRECONDITION} - the booking id was already charged with a different amount,
 *       currency or guest (rule I-2). Answering with the stored decision would hide the
 *       disagreement and retrying cannot help, so the call is refused.
 * </ul>
 *
 * <p>A declined charge is neither: it is an ordinary {@code ChargeResponse} with {@code DECLINED}
 * and a {@code decline_reason}, because the service did answer the question it was asked.
 */
@GrpcService
class PaymentGrpcEndpoint extends PaymentServiceGrpc.PaymentServiceImplBase {

  private static final int CURRENCY_LENGTH = 3;

  private final PaymentService payments;

  PaymentGrpcEndpoint(PaymentService payments) {
    this.payments = payments;
  }

  @Override
  public void charge(ChargeRequest request, StreamObserver<ChargeResponse> observer) {
    ChargeCommand command;
    try {
      command = commandOf(request);
    } catch (IllegalArgumentException invalid) {
      observer.onError(
          Status.INVALID_ARGUMENT.withDescription(invalid.getMessage()).asRuntimeException());
      return;
    }
    switch (payments.charge(command)) {
      case ChargeResult.Charged(Payment payment) -> answer(observer, responseOf(payment));
      case ChargeResult.AlreadyCharged(Payment payment) -> answer(observer, responseOf(payment));
      case ChargeResult.Mismatched(Payment payment, ChargeMismatch mismatch) ->
          observer.onError(mismatchStatus(payment, mismatch).asRuntimeException());
    }
  }

  @Override
  public void refund(RefundRequest request, StreamObserver<RefundResponse> observer) {
    UUID bookingId;
    try {
      bookingId = idOf(request.getBookingId(), "booking_id");
    } catch (IllegalArgumentException invalid) {
      observer.onError(
          Status.INVALID_ARGUMENT.withDescription(invalid.getMessage()).asRuntimeException());
      return;
    }
    boolean refunded =
        switch (payments.refund(new RefundCommand(bookingId))) {
          // The money is back, whether this call moved it or an earlier one did (rule I-1).
          case RefundResult.Refunded ignored -> true;
          case RefundResult.AlreadyRefunded ignored -> true;
          // Nothing was ever taken, so nothing went back: false rather than an error status, so a
          // caller and a redelivered event both get an answer they do not have to retry.
          case RefundResult.NotApproved ignored -> false;
          case RefundResult.NotCharged ignored -> false;
        };
    answer(observer, RefundResponse.newBuilder().setRefunded(refunded).build());
  }

  private static ChargeCommand commandOf(ChargeRequest request) {
    UUID bookingId = idOf(request.getBookingId(), "booking_id");
    UUID guestId = idOf(request.getGuestId(), "guest_id");
    if (request.getAmountMinor() <= 0) {
      throw new IllegalArgumentException(
          "amount_minor has to be positive, was " + request.getAmountMinor());
    }
    return new ChargeCommand(
        bookingId, guestId, request.getAmountMinor(), currencyOf(request.getCurrency()));
  }

  private static UUID idOf(String value, String field) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException notAnId) {
      throw new IllegalArgumentException(field + " has to be a UUID, was '" + value + "'", notAnId);
    }
  }

  private static String currencyOf(String currency) {
    if (currency.length() != CURRENCY_LENGTH) {
      throw new IllegalArgumentException(
          "currency has to be a three letter code, was '" + currency + "'");
    }
    return currency.toUpperCase(Locale.ROOT);
  }

  /** A refunded payment answers what the charge answered: it was approved once (rule I-1). */
  private static ChargeResponse responseOf(Payment payment) {
    return ChargeResponse.newBuilder()
        .setPaymentId(payment.getId().toString())
        .setStatus(statusOf(payment.getStatus()))
        .setDeclineReason(payment.getDeclineReason().orElse(""))
        .build();
  }

  private static ChargeResponse.Status statusOf(PaymentStatus status) {
    return switch (status) {
      case APPROVED, REFUNDED -> ChargeResponse.Status.APPROVED;
      case DECLINED -> ChargeResponse.Status.DECLINED;
    };
  }

  private static Status mismatchStatus(Payment payment, ChargeMismatch mismatch) {
    String description =
        switch (mismatch) {
          case GUEST ->
              "booking %s was charged for guest %s"
                  .formatted(payment.getBookingId(), payment.getGuestId());
          case AMOUNT ->
              "booking %s was charged %d %s"
                  .formatted(
                      payment.getBookingId(), payment.getAmountMinor(), payment.getCurrency());
          case CURRENCY ->
              "booking %s was charged in %s"
                  .formatted(payment.getBookingId(), payment.getCurrency());
        };
    return Status.FAILED_PRECONDITION.withDescription(
        "The booking id is the idempotency key and " + description);
  }

  private static <T> void answer(StreamObserver<T> observer, T response) {
    observer.onNext(response);
    observer.onCompleted();
  }
}
