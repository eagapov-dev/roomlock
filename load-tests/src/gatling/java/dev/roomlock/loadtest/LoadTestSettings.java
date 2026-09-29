package dev.roomlock.loadtest;

import static io.gatling.javaapi.http.HttpDsl.http;

import io.gatling.javaapi.http.HttpProtocolBuilder;
import java.time.Duration;

/**
 * Run parameters of every simulation, read from system properties of the Gradle invocation. The
 * defaults target the compose stack of {@code infra/docker-compose.yml}.
 *
 * <p>{@code strategy} is only a label: the locking strategy is chosen by the service itself ({@code
 * roomlock.locking.strategy}), the load test just carries the name into its output so runs of
 * different strategies can be told apart.
 */
final class LoadTestSettings {

  /** Base URL of booking-service. */
  static final String BASE_URL = property("roomlock.baseUrl", "http://localhost:8090");

  /** Label of the locking strategy the service under test is configured with. */
  static final String STRATEGY = property("roomlock.strategy", "unknown");

  /** Virtual users of the load phase. */
  static final int USERS = intProperty("roomlock.users", 50);

  /** How long the throughput phase keeps its users busy. */
  static final Duration DURATION = Duration.ofSeconds(intProperty("roomlock.duration", 30));

  /** Rooms created by the setup phase; the throughput phase spreads its holds over them. */
  static final int ROOMS = intProperty("roomlock.rooms", 20);

  private LoadTestSettings() {}

  /** JSON in, JSON (or problem+json) out, one connection pool per virtual user. */
  static HttpProtocolBuilder httpProtocol() {
    return http.baseUrl(BASE_URL)
        .contentTypeHeader("application/json")
        .acceptHeader("application/json, application/problem+json")
        .userAgentHeader("roomlock-load-tests");
  }

  /** Suffix of every scenario name, so the report says which run it belongs to. */
  static String label() {
    return " [strategy=" + STRATEGY + ", users=" + USERS + ", rooms=" + ROOMS + "]";
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
        + "s";
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
}
