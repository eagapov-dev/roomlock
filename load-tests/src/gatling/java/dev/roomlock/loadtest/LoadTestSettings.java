package dev.roomlock.loadtest;

import static io.gatling.javaapi.http.HttpDsl.http;

import io.gatling.javaapi.http.HttpProtocolBuilder;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;

/**
 * Run parameters of every simulation, read from system properties of the Gradle invocation. The
 * defaults target the compose stack of {@code infra/docker-compose.yml}.
 *
 * <p>{@code strategy} is only a label: the locking strategy is chosen by the service itself ({@code
 * roomlock.locking.strategy}), the load test just carries the name into its output so runs of
 * different strategies can be told apart. {@code paymentMode} is a label of the same kind, for the
 * decision mode of the payment provider behind the confirm flow.
 */
final class LoadTestSettings {

  /** Base URL of booking-service. */
  static final String BASE_URL = property("roomlock.baseUrl", "http://localhost:8090");

  /** Label of the locking strategy the service under test is configured with. */
  static final String STRATEGY = property("roomlock.strategy", "unknown");

  /**
   * An access token minted elsewhere. Set it to point a run at an issuer that has no client
   * credentials flow, or to reuse a token of a shell session; when it is empty the four parameters
   * below are exchanged for one instead (see {@link LoadTestToken}).
   */
  static final String TOKEN = property("roomlock.token", "");

  /** Token endpoint of the issuer booking-service trusts. */
  static final String TOKEN_URL =
      property(
          "roomlock.tokenUrl",
          "http://localhost:8088/realms/roomlock/protocol/openid-connect/token");

  /**
   * Client the run authenticates as. The default is the operator client of
   * infra/keycloak/roomlock-realm.json, whose token carries both halves a run needs: the
   * catalog:write scope of the warmup, and a subject to hold and pay as.
   */
  static final String CLIENT_ID = property("roomlock.clientId", "roomlock-operator");

  /** Secret of that client. A throwaway of the local stack; any other issuer is given its own. */
  static final String CLIENT_SECRET = property("roomlock.clientSecret", "roomlock-operator-secret");

  /**
   * Scopes to ask for, if the issuer does not attach them by default. Empty against the local
   * realm, which gives the operator client catalog:write without being asked.
   */
  static final String SCOPE = property("roomlock.scope", "");

  /** Virtual users of the load phase. */
  static final int USERS = intProperty("roomlock.users", 50);

  /** How long the throughput phase keeps its users busy. */
  static final Duration DURATION = Duration.ofSeconds(intProperty("roomlock.duration", 30));

  /** Rooms created by the setup phase; the throughput phase spreads its holds over them. */
  static final int ROOMS = intProperty("roomlock.rooms", 20);

  /** Price per night of the rooms the setup phase creates; a one-night stay costs exactly this. */
  static final String PRICE_PER_NIGHT = decimalProperty("roomlock.pricePerNight", "120.00");

  /**
   * Price per night of the decline flow: above the limit of the payment provider in {@code
   * THRESHOLD} mode ({@code roomlock.payment.decision.decline-above-minor}, 1000.00 PLN by
   * default), so a one-night stay is declined on purpose and the provider needs no reconfiguration.
   */
  static final String DECLINED_PRICE_PER_NIGHT =
      decimalProperty("roomlock.declinedPricePerNight", "2500.00");

  /**
   * Label of the decision mode the payment provider under test runs with: {@code APPROVE_ALL},
   * {@code DECLINE_ALL} or {@code THRESHOLD}. Like {@link #STRATEGY} it only reaches the output,
   * the provider is configured by its own deployment ({@code roomlock.payment.decision.mode}).
   */
  static final String PAYMENT_MODE = property("roomlock.paymentMode", "THRESHOLD");

  private LoadTestSettings() {}

  /**
   * JSON in, JSON (or problem+json) out, one connection pool per virtual user, and the access token
   * of the run on every request - the catalog writes of the warmup and everything under
   * /api/v1/bookings need one, and the two catalog reads are simply unbothered by it.
   */
  static HttpProtocolBuilder httpProtocol() {
    return http.baseUrl(BASE_URL)
        .contentTypeHeader("application/json")
        .acceptHeader("application/json, application/problem+json")
        .authorizationHeader(LoadTestToken.bearer())
        .userAgentHeader("roomlock-load-tests");
  }

  /** Suffix of every scenario name, so the report says which run it belongs to. */
  static String label() {
    return label("");
  }

  /**
   * The same suffix for a scenario that pays: the mode of the provider decides what it may expect.
   */
  static String paymentLabel() {
    return label(", payment=" + PAYMENT_MODE);
  }

  /** One banner line per run, printed before the injection starts. */
  static String banner(String simulation) {
    return "roomlock load test: "
        + simulation
        + " against "
        + BASE_URL
        + ", strategy="
        + STRATEGY
        + ", users="
        + USERS
        + ", rooms="
        + ROOMS
        + ", duration="
        + DURATION.toSeconds()
        + "s, token from "
        + LoadTestToken.source();
  }

  /** The same banner for a scenario that pays. */
  static String paymentBanner(String simulation) {
    return banner(simulation) + ", payment mode=" + PAYMENT_MODE;
  }

  private static String label(String extra) {
    return " [strategy=" + STRATEGY + ", users=" + USERS + ", rooms=" + ROOMS + extra + "]";
  }

  private static String property(String name, String fallback) {
    String value = System.getProperty(name);
    return value == null || value.isBlank() ? fallback : value.trim();
  }

  private static int intProperty(String name, int fallback) {
    String value = System.getProperty(name);
    if (value == null || value.isBlank()) {
      return fallback;
    }
    int parsed = Integer.parseInt(value.trim());
    if (parsed < 1) {
      throw new IllegalArgumentException(name + " must be positive, got " + parsed);
    }
    return parsed;
  }

  /** An amount of money, in the two decimals the booking API answers prices with. */
  private static String decimalProperty(String name, String fallback) {
    BigDecimal parsed = new BigDecimal(property(name, fallback));
    if (parsed.signum() < 1) {
      throw new IllegalArgumentException(name + " must be positive, got " + parsed);
    }
    return parsed.setScale(2, RoundingMode.HALF_UP).toPlainString();
  }
}
