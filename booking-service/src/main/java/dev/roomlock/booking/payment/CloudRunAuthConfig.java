package dev.roomlock.booking.payment;

import dev.roomlock.booking.Profiles;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Authentication of the calls to payment-service, on Cloud Run and nowhere else.
 *
 * <p>Nothing here exists under any other profile, and that absence is the local behaviour: without
 * a {@link CloudRunAuthInterceptor} bean {@link PaymentChannelConfig} builds the stub on the bare
 * channel, exactly as it always has.
 */
@Configuration(proxyBeanMethods = false)
@Profile(Profiles.CLOUD)
class CloudRunAuthConfig {

  /**
   * The audience is the URL of the payment-service revision - the host {@code
   * spring.grpc.client.channels.payment} dials, as https and without the port. A deployment that
   * does not set it does not start, which is better than every charge being refused.
   */
  @Bean
  IdTokenSource paymentIdTokens(@Value("${roomlock.cloud.payment-audience}") String audience) {
    return new MetadataServerIdTokens(audience);
  }

  @Bean
  CloudRunAuthInterceptor paymentAuthentication(IdTokenSource tokens) {
    return new CloudRunAuthInterceptor(tokens);
  }
}
