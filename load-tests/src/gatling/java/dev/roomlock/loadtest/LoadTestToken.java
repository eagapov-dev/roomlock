package dev.roomlock.loadtest;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The access token every request of a run carries.
 *
 * <p>Fetched once per JVM and reused, not once per virtual user: booking-service validates a token
 * against a cached JWK set and never asks the issuer again, so a token per user would measure
 * Keycloak's throughput rather than the locking strategy this run exists to compare - and would put
 * a login flow in front of every hold that is nothing like what a real client does.
 *
 * <p>Two ways in, because a run has to be able to point at any issuer. {@code roomlock.token} takes
 * a token that was minted elsewhere and asks nothing of any provider; otherwise the client
 * credentials of {@link LoadTestSettings} are exchanged at {@code roomlock.tokenUrl}. The default
 * is the Keycloak of {@code infra/docker-compose.yml} and its {@code roomlock-operator} client,
 * which carries both what a run needs: the {@code catalog:write} scope for the hotel and rooms of
 * the warmup, and a subject to hold and pay as.
 *
 * <p>One token means one guest for the whole run, which changes nothing about what is measured: the
 * idempotency key is per virtual user, and the overlap of a stay is decided by the room and the
 * night, not by who asks. It does mean a run cannot outlive the token - the realm gives 30 minutes,
 * well past any simulation here.
 */
final class LoadTestToken {

  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  /**
   * The token endpoint answers JSON, and this module declares no dependencies at all so that the
   * Gatling version stays pinned in one place (see its build file). One field of one flat object is
   * not worth breaking that for.
   */
  private static final Pattern ACCESS_TOKEN =
      Pattern.compile("\"access_token\"\\s*:\\s*\"([^\"]+)\"");

  private static final String VALUE = fetch();

  private LoadTestToken() {}

  /** Value of the {@code Authorization} header of every request of this run. */
  static String bearer() {
    return "Bearer " + VALUE;
  }

  /** Where the token came from, for the banner of the run. */
  static String source() {
    return LoadTestSettings.TOKEN.isEmpty()
        ? LoadTestSettings.TOKEN_URL + " as " + LoadTestSettings.CLIENT_ID
        : "-Droomlock.token";
  }

  private static String fetch() {
    if (!LoadTestSettings.TOKEN.isEmpty()) {
      return LoadTestSettings.TOKEN;
    }
    HttpResponse<String> response = post(form());
    if (response.statusCode() != 200) {
      throw new IllegalStateException(
          "The identity provider at "
              + LoadTestSettings.TOKEN_URL
              + " refused the client credentials of "
              + LoadTestSettings.CLIENT_ID
              + ": HTTP "
              + response.statusCode()
              + " "
              + response.body());
    }
    Matcher token = ACCESS_TOKEN.matcher(response.body());
    if (!token.find()) {
      throw new IllegalStateException(
          "The answer of " + LoadTestSettings.TOKEN_URL + " carries no access_token");
    }
    return token.group(1);
  }

  private static String form() {
    StringBuilder form =
        new StringBuilder("grant_type=client_credentials")
            .append("&client_id=")
            .append(encoded(LoadTestSettings.CLIENT_ID))
            .append("&client_secret=")
            .append(encoded(LoadTestSettings.CLIENT_SECRET));
    if (!LoadTestSettings.SCOPE.isEmpty()) {
      form.append("&scope=").append(encoded(LoadTestSettings.SCOPE));
    }
    return form.toString();
  }

  private static HttpResponse<String> post(String form) {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(LoadTestSettings.TOKEN_URL))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .timeout(TIMEOUT)
            .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
            .build();
    try (HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()) {
      return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (IOException failure) {
      throw new IllegalStateException(
          "Cannot reach the identity provider at " + LoadTestSettings.TOKEN_URL, failure);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while fetching the access token", interrupted);
    }
  }

  private static String encoded(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
