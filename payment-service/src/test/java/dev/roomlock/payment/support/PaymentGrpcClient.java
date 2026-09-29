package dev.roomlock.payment.support;

import dev.roomlock.payment.api.v1.ChargeRequest;
import dev.roomlock.payment.api.v1.ChargeResponse;
import dev.roomlock.payment.api.v1.PaymentServiceGrpc;
import dev.roomlock.payment.api.v1.PaymentServiceGrpc.PaymentServiceBlockingStub;
import dev.roomlock.payment.api.v1.RefundRequest;
import dev.roomlock.payment.api.v1.RefundResponse;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import java.util.UUID;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.core.env.Environment;
import org.springframework.lang.Nullable;

/**
 * Calls the real gRPC server of the running application over a real channel (rule T-4), instead of
 * the in-process transport: the transport, the generated stubs and the interceptors are part of
 * what the tests are about.
 *
 * <p>The channel is opened on first use. The port only exists once the server has started, which
 * happens after the context has created its singletons, so it cannot be injected into the
 * constructor.
 */
@TestComponent
public class PaymentGrpcClient implements AutoCloseable {

  /** Published by spring-grpc-test once a server started on port 0 has bound its port. */
  private static final String PORT_PROPERTY = "local.grpc.port";

  private final Environment environment;

  @Nullable private ManagedChannel channel;
  @Nullable private PaymentServiceBlockingStub payments;

  public PaymentGrpcClient(Environment environment) {
    this.environment = environment;
  }

  public ChargeResponse charge(ChargeRequest request) {
    return stub().charge(request);
  }

  public RefundResponse refund(UUID bookingId) {
    return stub().refund(RefundRequest.newBuilder().setBookingId(bookingId.toString()).build());
  }

  /** A refund of an id the caller got wrong, for the argument checks of the endpoint. */
  public RefundResponse refundRaw(String bookingId) {
    return stub().refund(RefundRequest.newBuilder().setBookingId(bookingId).build());
  }

  @Override
  public synchronized void close() {
    if (channel != null) {
      channel.shutdownNow();
      channel = null;
      payments = null;
    }
  }

  private synchronized PaymentServiceBlockingStub stub() {
    if (payments == null) {
      int port = environment.getRequiredProperty(PORT_PROPERTY, Integer.class);
      channel =
          Grpc.newChannelBuilderForAddress("localhost", port, InsecureChannelCredentials.create())
              .build();
      payments = PaymentServiceGrpc.newBlockingStub(channel);
    }
    return payments;
  }
}
