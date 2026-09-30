package dev.roomlock.booking.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * The issuer of the test suite: the real {@link NimbusJwtDecoder} the application would build,
 * given the public key of {@link TestJwts} instead of a JWK set fetched over the network.
 *
 * <p>This bean replaces the one {@code spring.security.oauth2.resourceserver.jwt.*} would have
 * produced (Spring Boot backs off when a {@code JwtDecoder} is present), which is what lets the
 * whole suite run with no identity provider and no issuer property. What it does <em>not</em>
 * replace is the validation: the signature, the expiry and the {@code iss} claim are checked
 * exactly as in production, so an expired or foreign-signed token is refused here for the
 * production reason.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestSecurityConfig {

  @Bean
  JwtDecoder jwtDecoder() {
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(TestJwts.publicKey()).build();
    OAuth2TokenValidator<Jwt> validators = JwtValidators.createDefaultWithIssuer(TestJwts.ISSUER);
    decoder.setJwtValidator(validators);
    return decoder;
  }
}
