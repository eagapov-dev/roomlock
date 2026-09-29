package dev.roomlock.booking.payment;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import java.io.IOException;

/**
 * Puts a Google-signed ID token on every call of the payment contract.
 *
 * <p>On Cloud Run payment-service takes internal traffic only and requires authentication: the
 * caller sends a token whose audience is the URL of the callee, and the platform refuses a call
 * without one before the service ever sees it. Only the {@code cloud} profile has this interceptor
 * ({@link CloudRunAuthConfig}) - a local run has no metadata server to sign a token, and the
 * payment-service of the compose stack asks for nothing.
 *
 * <p>A token that cannot be signed fails the call with {@code UNAUTHENTICATED} instead of letting
 * an {@link IOException} escape: {@link GrpcPaymentGateway} reads that as no answer, so the booking
 * stays PAYING and the reconciliation sweep asks again - which is the right move, because a token
 * the metadata server refused says nothing about the money. The status is thrown where the call is
 * created, which is inside the blocking stub the gateway calls, so it reaches the gateway as any
 * other failed call does.
 */
class CloudRunAuthInterceptor implements ClientInterceptor {

  /** The header Cloud Run reads the token from. */
  static final Metadata.Key<String> AUTHORIZATION =
      Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

  private static final String BEARER = "Bearer ";

  private final IdTokenSource tokens;

  CloudRunAuthInterceptor(IdTokenSource tokens) {
    this.tokens = tokens;
  }

  @Override
  public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
      MethodDescriptor<ReqT, RespT> method, CallOptions options, Channel next) {
    String token = token();
    return new SimpleForwardingClientCall<>(next.newCall(method, options)) {

      @Override
      public void start(Listener<RespT> listener, Metadata headers) {
        headers.put(AUTHORIZATION, BEARER + token);
        super.start(listener, headers);
      }
    };
  }

  private String token() {
    try {
      return tokens.token();
    } catch (IOException noToken) {
      throw Status.UNAUTHENTICATED
          .withDescription("No ID token of payment-service could be signed")
          .withCause(noToken)
          .asRuntimeException();
    }
  }
}
