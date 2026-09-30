package dev.roomlock.booking.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariConfig;
import dev.roomlock.booking.Profiles;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The {@code cloud} document of application.yml: that it is read at all, and that every value the
 * deployment owns comes from the environment variable docs/deployment.md names.
 *
 * <p>Only the configuration is loaded - no auto-configuration, so no datasource is opened, no
 * broker is dialled and no channel is built, and no GCP project is needed to run this. What is
 * asserted is the resolved property, which is what those beans would be built from.
 */
class CloudProfileTest {

  /** Every variable the revision sets, with values of no real project. */
  private static final String[] ENVIRONMENT = {
    "DB_HOST=10.20.0.3",
    "DB_NAME=booking",
    "DB_USER=booking",
    "DB_PASSWORD=not-a-real-password",
    "KAFKA_BOOTSTRAP_SERVERS=bootstrap.roomlock.example.managedkafka:9092",
    "PAYMENT_GRPC_ADDRESS=dns:///payment-service.example.run.app:443",
    "PAYMENT_AUDIENCE=https://payment-service.example.run.app",
    "OIDC_ISSUER_URI=https://issuer.example.com/realms/roomlock",
    "OIDC_JWK_SET_URI=https://issuer.example.com/realms/roomlock/protocol/openid-connect/certs",
  };

  @Test
  void shouldReachCloudSqlOverHostAndPortWhenProfileIsCloud() {
    // given
    // when
    String url = resolved("spring.datasource.url", ENVIRONMENT);

    // then
    assertThat(url).isEqualTo("jdbc:postgresql://10.20.0.3:5432/booking");
  }

  @Test
  void shouldSizeThePoolForManyInstancesWhenProfileIsCloud() {
    // given
    // when
    Integer poolSize =
        resolvedAs("spring.datasource.hikari.maximum-pool-size", Integer.class, ENVIRONMENT);

    // then
    assertThat(poolSize).isEqualTo(5);
  }

  /**
   * The pool settings have to bind to {@link HikariConfig}, not merely resolve. Its setters take a
   * long of milliseconds, so a duration like {@code 5s} resolves perfectly well as a string and
   * then fails the context at startup - which no assertion on a resolved property can see, and
   * which nobody would find until a revision refused to start.
   */
  @Test
  void shouldBindEveryPoolSettingToHikariWhenProfileIsCloud() {
    // given
    AtomicReference<HikariConfig> bound = new AtomicReference<>();

    // when
    new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withPropertyValues("spring.profiles.active=" + Profiles.CLOUD)
        .withPropertyValues(ENVIRONMENT)
        .run(
            context ->
                bound.set(
                    Binder.get(context.getEnvironment())
                        .bind("spring.datasource.hikari", Bindable.of(HikariConfig.class))
                        .orElseThrow(
                            () ->
                                new AssertionError(
                                    "spring.datasource.hikari is absent from the cloud document"))));

    // then
    HikariConfig pool = bound.get();
    assertThat(pool.getMaximumPoolSize()).isEqualTo(5);
    assertThat(pool.getConnectionTimeout()).isPositive();
    assertThat(pool.getMaxLifetime()).isPositive();
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
    String[] withoutPassword = {"DB_HOST=10.20.0.3", "DB_NAME=booking", "DB_USER=booking"};

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

  @Test
  void shouldNegotiateTlsToPaymentServiceWhenProfileIsCloud() {
    // given
    // when
    String address = resolved("spring.grpc.client.channels.payment.address", ENVIRONMENT);
    String negotiation =
        resolved("spring.grpc.client.channels.payment.negotiation-type", ENVIRONMENT);

    // then
    assertThat(address).isEqualTo("dns:///payment-service.example.run.app:443");
    assertThat(negotiation).isEqualTo("tls");
  }

  @Test
  void shouldNameTheAudienceOfTheIdTokenWhenProfileIsCloud() {
    // given
    // when
    String audience = resolved("roomlock.cloud.payment-audience", ENVIRONMENT);

    // then
    assertThat(audience).isEqualTo("https://payment-service.example.run.app");
  }

  /**
   * Which identity provider a revision trusts is a deployment decision, not a default - and both
   * values have to come from the environment, because the documents of application.yml are merged
   * and an unset jwk-set-uri would inherit the localhost Keycloak of a local run.
   */
  @Test
  void shouldTakeTheIssuerAndItsKeysFromTheEnvironmentWhenProfileIsCloud() {
    // given
    // when
    String issuer = resolved("spring.security.oauth2.resourceserver.jwt.issuer-uri", ENVIRONMENT);
    String keys = resolved("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", ENVIRONMENT);

    // then
    assertThat(issuer).isEqualTo("https://issuer.example.com/realms/roomlock");
    assertThat(keys)
        .isEqualTo("https://issuer.example.com/realms/roomlock/protocol/openid-connect/certs")
        .doesNotContain("localhost");
  }

  /**
   * A default would be worse than a failure: it would be a service quietly trusting somebody else's
   * identity provider. The name is a contract, and a missing one has to be loud.
   */
  @Test
  void shouldRefuseTheIssuerWhenItsVariableIsNotSet() {
    // given
    String[] withoutIssuer = {"DB_HOST=10.20.0.3", "DB_NAME=booking", "DB_USER=booking"};

    // when
    // then
    assertThatThrownBy(
            () -> resolved("spring.security.oauth2.resourceserver.jwt.issuer-uri", withoutIssuer))
        .hasMessageContaining("OIDC_ISSUER_URI");
  }

  @Test
  void shouldRefuseTheJwkSetUriWhenItsVariableIsNotSet() {
    // given
    String[] withoutKeys = {"OIDC_ISSUER_URI=https://issuer.example.com/realms/roomlock"};

    // when
    // then
    assertThatThrownBy(
            () -> resolved("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", withoutKeys))
        .hasMessageContaining("OIDC_JWK_SET_URI");
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
    String negotiation =
        propertyOf(local, "spring.grpc.client.channels.payment.negotiation-type", String.class);

    // then
    assertThat(url).isEqualTo("jdbc:postgresql://localhost:5433/booking");
    assertThat(negotiation).isEqualTo("plaintext");
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
