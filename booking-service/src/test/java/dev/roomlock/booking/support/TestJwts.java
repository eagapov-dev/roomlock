package dev.roomlock.booking.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * Access tokens for tests, signed with a key pair generated in this JVM (rule T-5 in spirit: real
 * mechanism, no external dependency).
 *
 * <p>No identity provider takes part in the test suite. A suite that needs a Keycloak container to
 * run is a suite people stop running, and the thing under test is not Keycloak - it is that this
 * service refuses a token it cannot verify and trusts one it can. So the tokens here are real RS256
 * JWTs and {@link TestSecurityConfig} gives the application the matching public key; the validation
 * that happens is the production one, only the issuer is local.
 */
public final class TestJwts {

  /** Issuer every token below claims, and the only one {@link TestSecurityConfig} accepts. */
  public static final String ISSUER = "https://issuer.roomlock.test";

  /** Scope an operator token carries; matches {@code ApiSecurityConfig.CATALOG_WRITE}. */
  public static final String CATALOG_WRITE = "catalog:write";

  private static final Duration LIFETIME = Duration.ofMinutes(5);

  /** The key the application trusts. */
  private static final RSAKey KEY = generate();

  /** A key it has never heard of, for a token that is signed but not by the issuer. */
  private static final RSAKey FOREIGN_KEY = generate();

  private TestJwts() {}

  /** The public half of the key pair, for the decoder of {@link TestSecurityConfig}. */
  public static java.security.interfaces.RSAPublicKey publicKey() {
    try {
      return KEY.toRSAPublicKey();
    } catch (JOSEException failure) {
      throw new IllegalStateException("Cannot read the test public key", failure);
    }
  }

  /** A token of a guest with a fresh id. */
  public static String guest() {
    return guest(UUID.randomUUID());
  }

  /** A token whose subject is this guest id, and which carries no scope. */
  public static String guest(UUID guestId) {
    return signedBy(KEY, claims(guestId.toString(), null, Instant.now().plus(LIFETIME)));
  }

  /** A token of an operator: a subject of its own plus the scope that writes the catalog. */
  public static String operator() {
    return signedBy(
        KEY, claims(UUID.randomUUID().toString(), CATALOG_WRITE, Instant.now().plus(LIFETIME)));
  }

  /** A token with an arbitrary subject, for the guest id a non-UUID subject is mapped to. */
  public static String subject(String subject) {
    return signedBy(KEY, claims(subject, null, Instant.now().plus(LIFETIME)));
  }

  /** A token of this guest that expired a minute ago. */
  public static String expired(UUID guestId) {
    return signedBy(
        KEY, claims(guestId.toString(), null, Instant.now().minus(Duration.ofMinutes(1))));
  }

  /** A well-formed token of this guest, signed with a key the application does not trust. */
  public static String wronglySigned(UUID guestId) {
    return signedBy(FOREIGN_KEY, claims(guestId.toString(), null, Instant.now().plus(LIFETIME)));
  }

  /** {@code Authorization: Bearer <token>}. */
  public static HttpHeaders bearer(String token) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(token);
    return headers;
  }

  /** The same, for a request that also carries a JSON body. */
  public static HttpHeaders jsonBearer(String token) {
    HttpHeaders headers = bearer(token);
    headers.setContentType(MediaType.APPLICATION_JSON);
    return headers;
  }

  private static JWTClaimsSet claims(String subject, String scope, Instant expiresAt) {
    JWTClaimsSet.Builder claims =
        new JWTClaimsSet.Builder()
            .issuer(ISSUER)
            .subject(subject)
            .jwtID(UUID.randomUUID().toString())
            .issueTime(Date.from(Instant.now().minus(Duration.ofSeconds(30))))
            .expirationTime(Date.from(expiresAt));
    if (scope != null) {
      claims.claim("scope", scope);
    }
    return claims.build();
  }

  private static String signedBy(RSAKey key, JWTClaimsSet claims) {
    try {
      SignedJWT token =
          new SignedJWT(
              new JWSHeader.Builder(JWSAlgorithm.RS256)
                  .type(JOSEObjectType.JWT)
                  .keyID(key.getKeyID())
                  .build(),
              claims);
      token.sign(new RSASSASigner(key));
      return token.serialize();
    } catch (JOSEException failure) {
      throw new IllegalStateException("Cannot sign a test token", failure);
    }
  }

  private static RSAKey generate() {
    try {
      return new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
    } catch (JOSEException failure) {
      throw new IllegalStateException("Cannot generate a test key pair", failure);
    }
  }
}
