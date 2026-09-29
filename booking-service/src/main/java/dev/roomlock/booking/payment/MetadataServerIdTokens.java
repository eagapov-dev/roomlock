package dev.roomlock.booking.payment;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.IdTokenCredentials;
import com.google.auth.oauth2.IdTokenProvider;
import java.io.IOException;

/**
 * The {@link IdTokenSource} of a Cloud Run instance: Google's auth library asks the metadata server
 * for a token signed for this audience and keeps it until it expires.
 *
 * <p>The credentials are read on the first call, not in the constructor: reading them talks to the
 * metadata server, and a service must not fail to start over a provider it has not called yet - the
 * same reason the channel of {@link PaymentChannelConfig} connects lazily.
 */
final class MetadataServerIdTokens implements IdTokenSource {

  private final String audience;

  private volatile IdTokenCredentials credentials;

  MetadataServerIdTokens(String audience) {
    if (audience == null || audience.isBlank()) {
      throw new IllegalArgumentException("A token of payment-service needs an audience");
    }
    this.audience = audience;
  }

  @Override
  public String token() throws IOException {
    IdTokenCredentials tokens = credentials();
    tokens.refreshIfExpired();
    return tokens.getIdToken().getTokenValue();
  }

  private IdTokenCredentials credentials() throws IOException {
    IdTokenCredentials known = credentials;
    if (known != null) {
      return known;
    }
    synchronized (this) {
      if (credentials == null) {
        credentials = of(audience);
      }
      return credentials;
    }
  }

  /**
   * The service account of the instance. Only an account that can sign one - a service account, not
   * a user - is an {@link IdTokenProvider}, so a run outside GCP fails here rather than sending a
   * call without a token.
   */
  private static IdTokenCredentials of(String audience) throws IOException {
    GoogleCredentials account = GoogleCredentials.getApplicationDefault();
    if (!(account instanceof IdTokenProvider provider)) {
      throw new IOException(
          "The credentials of this instance cannot sign ID tokens: "
              + account.getClass().getName());
    }
    return IdTokenCredentials.newBuilder()
        .setIdTokenProvider(provider)
        .setTargetAudience(audience)
        .build();
  }
}
