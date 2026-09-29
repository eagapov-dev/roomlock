package dev.roomlock.booking.payment;

import dev.roomlock.payment.api.v1.PaymentServiceGrpc;
import dev.roomlock.payment.api.v1.PaymentServiceGrpc.PaymentServiceBlockingStub;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;

/**
 * The stub {@link GrpcPaymentGateway} calls. Spring gRPC builds the channel from {@code
 * spring.grpc.client.channels.payment}, so the address of payment-service is configuration, not
 * code.
 *
 * <p>A blocking stub on purpose: the flow of a paying request is sequential, and virtual threads
 * (see {@code spring.threads.virtual}) make the blocked carrier a non-issue.
 */
@Configuration(proxyBeanMethods = false)
class PaymentChannelConfig {

  /** Name of the channel in {@code spring.grpc.client.channels}. */
  static final String CHANNEL = "payment";

  /**
   * The channel is created eagerly but connects lazily, on the first call: a payment-service that
   * is not up yet does not keep booking-service from starting.
   */
  @Bean
  PaymentServiceBlockingStub paymentStub(GrpcChannelFactory channels) {
    return PaymentServiceGrpc.newBlockingStub(channels.createChannel(CHANNEL));
  }
}
