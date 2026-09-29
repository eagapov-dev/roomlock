package dev.roomlock.booking.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.roomlock.payment.api.v1.ChargeRequest;
import dev.roomlock.payment.api.v1.ChargeResponse;
import dev.roomlock.payment.api.v1.PaymentServiceGrpc;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptors;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * What a charge carries once the interceptor is in the channel, with a stubbed source of tokens:
 * the metadata server of a Cloud Run instance is not on this classpath, and what has to be pinned
 * is the header, not how the token was signed.
 */
class CloudRunAuthInterceptorTest {

  private static final MethodDescriptor<ChargeRequest, ChargeResponse> CHARGE =
      PaymentServiceGrpc.getChargeMethod();

  @Test
  void shouldCarryTheIdTokenAsABearerTokenWhenTheCallStarts() {
    // given
    CapturingChannel channel = new CapturingChannel();
    Channel authenticated = intercepted(channel, () -> "token-of-the-instance");

    // when
    authenticated
        .newCall(CHARGE, CallOptions.DEFAULT)
        .start(new ClientCall.Listener<>() {}, new Metadata());

    // then
    assertThat(channel.headers().get(CloudRunAuthInterceptor.AUTHORIZATION))
        .isEqualTo("Bearer token-of-the-instance");
  }

  /**
   * A metadata server that refuses is not an answer about the money: the call fails the way an
   * unreachable provider does, which {@link GrpcPaymentGateway} turns into Unavailable and the
   * reconciliation sweep asks again.
   */
  @Test
  void shouldFailTheCallWhenNoTokenCanBeSigned() {
    // given
    Channel authenticated =
        intercepted(
            new CapturingChannel(),
            () -> {
              throw new IOException("no metadata server");
            });

    // when
    // then
    assertThatThrownBy(() -> authenticated.newCall(CHARGE, CallOptions.DEFAULT))
        .isInstanceOf(StatusRuntimeException.class)
        .hasMessageContaining("UNAUTHENTICATED");
  }

  @Test
  void shouldSendNoTokenWhenTheChannelIsNotIntercepted() {
    // given
    CapturingChannel channel = new CapturingChannel();

    // when
    channel
        .newCall(CHARGE, CallOptions.DEFAULT)
        .start(new ClientCall.Listener<>() {}, new Metadata());

    // then
    assertThat(channel.headers().get(CloudRunAuthInterceptor.AUTHORIZATION)).isNull();
  }

  private static Channel intercepted(Channel channel, IdTokenSource tokens) {
    return ClientInterceptors.intercept(channel, new CloudRunAuthInterceptor(tokens));
  }

  /** A channel that sends nothing and only remembers the metadata a call was started with. */
  private static final class CapturingChannel extends Channel {

    private final AtomicReference<Metadata> headers = new AtomicReference<>();

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> newCall(
        MethodDescriptor<ReqT, RespT> method, CallOptions options) {
      return new ClientCall<>() {

        @Override
        public void start(Listener<RespT> listener, Metadata metadata) {
          headers.set(metadata);
        }

        @Override
        public void request(int messages) {}

        @Override
        public void cancel(String message, Throwable cause) {}

        @Override
        public void halfClose() {}

        @Override
        public void sendMessage(ReqT message) {}
      };
    }

    @Override
    public String authority() {
      return "capturing";
    }

    Metadata headers() {
      return headers.get();
    }
  }
}
