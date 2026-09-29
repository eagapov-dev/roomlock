package dev.roomlock.notification.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.roomlock.notification.Profiles;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The {@code cloud} document of application.yml: that it is read at all, and that every value the
 * deployment owns comes from the environment variable docs/deployment.md names.
 *
 * <p>Only the configuration is loaded - no auto-configuration, so no datasource is opened and no
 * broker is dialled, and no GCP project is needed to run this. What is asserted is the resolved
 * property, which is what those beans would be built from.
 */
class CloudProfileTest {

  /** Every variable the revision sets, with values of no real project. */
  private static final String[] ENVIRONMENT = {
    "PORT=8080",
    "DB_HOST=10.20.0.3",
    "DB_NAME=notification",
    "DB_USER=notification",
    "DB_PASSWORD=not-a-real-password",
    "KAFKA_BOOTSTRAP_SERVERS=bootstrap.roomlock.example.managedkafka:9092",
  };

  @Test
  void shouldReachCloudSqlOverHostAndPortWhenProfileIsCloud() {
    // given
    // when
    String url = resolved("spring.datasource.url", ENVIRONMENT);

    // then
    assertThat(url).isEqualTo("jdbc:postgresql://10.20.0.3:5432/notification");
  }

  @Test
  void shouldTakeTheDatabasePasswordFromTheEnvironmentWhenProfileIsCloud() {
    // given
    // when
    String password = resolved("spring.datasource.password", ENVIRONMENT);

    // then
    assertThat(password).isEqualTo("not-a-real-password");
  }

  /** The names above are a contract, not a convenience: a missing one has to be loud. */
  @Test
  void shouldRefuseThePasswordWhenItsVariableIsNotSet() {
    // given
    String[] withoutPassword = {
      "DB_HOST=10.20.0.3", "DB_NAME=notification", "DB_USER=notification"
    };

    // when
    // then
    assertThatThrownBy(() -> resolved("spring.datasource.password", withoutPassword))
        .hasMessageContaining("DB_PASSWORD");
  }

  @Test
  void shouldAuthenticateToTheManagedBrokerWhenProfileIsCloud() {
    // given
    // when
    String brokers = resolved("spring.kafka.bootstrap-servers", ENVIRONMENT);
    String protocol = resolved("spring.kafka.security.protocol", ENVIRONMENT);
    String mechanism = resolved("spring.kafka.properties.sasl.mechanism", ENVIRONMENT);
    String handler =
        resolved("spring.kafka.properties.sasl.login.callback.handler.class", ENVIRONMENT);

    // then
    assertThat(brokers).isEqualTo("bootstrap.roomlock.example.managedkafka:9092");
    assertThat(protocol).isEqualTo("SASL_SSL");
    assertThat(mechanism).isEqualTo("OAUTHBEARER");
    assertThat(handler).isEqualTo("com.google.cloud.hosted.kafka.auth.GcpLoginCallbackHandler");
  }

  /** The consumer serves no API, but the revision still has to listen on the port it is given. */
  @Test
  void shouldListenOnThePortCloudRunGivesWhenProfileIsCloud() {
    // given
    // when
    Integer port = resolvedAs("server.port", Integer.class, ENVIRONMENT);

    // then
    assertThat(port).isEqualTo(8080);
  }

  /** Flyway on startup and the two endpoints Cloud Run and Managed Prometheus read. */
  @Test
  void shouldKeepMigrationsAndTheEndpointsWhenProfileIsCloud() {
    // given
    // when
    Boolean flyway = resolvedAs("spring.flyway.enabled", Boolean.class, ENVIRONMENT);
    String endpoints = resolved("management.endpoints.web.exposure.include", ENVIRONMENT);
    Boolean probes =
        resolvedAs("management.endpoint.health.probes.enabled", Boolean.class, ENVIRONMENT);

    // then
    assertThat(flyway).isTrue();
    assertThat(endpoints).contains("health").contains("prometheus");
    assertThat(probes).isTrue();
  }

  /** Nothing of the cloud document may reach a run that did not ask for it. */
  @Test
  void shouldKeepTheLocalDefaultsWhenProfileIsNotCloud() {
    // given
    ApplicationContextRunner local =
        new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer());

    // when
    String url = propertyOf(local, "spring.datasource.url", String.class);
    Integer port = propertyOf(local, "server.port", Integer.class);

    // then
    assertThat(url).isEqualTo("jdbc:postgresql://localhost:5433/notification");
    assertThat(port).isEqualTo(8082);
  }

  private static String resolved(String property, String... environment) {
    return propertyOf(cloudWith(environment), property, String.class);
  }

  /** The same, converted: a value the deployment reads as a number has to be one. */
  private static <T> T resolvedAs(String property, Class<T> type, String... environment) {
    return propertyOf(cloudWith(environment), property, type);
  }

  /** The configuration of a revision: the {@code cloud} profile and the variables it is given. */
  private static ApplicationContextRunner cloudWith(String... environment) {
    return new ApplicationContextRunner()
        .withInitializer(context -> context.getEnvironment().setActiveProfiles(Profiles.CLOUD))
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withPropertyValues(environment);
  }

  private static <T> T propertyOf(ApplicationContextRunner runner, String property, Class<T> type) {
    AtomicReference<T> value = new AtomicReference<>();
    runner.run(context -> value.set(context.getEnvironment().getProperty(property, type)));
    return value.get();
  }
}
