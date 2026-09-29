package dev.roomlock.booking.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Infrastructure containers for booking-service tests (rule T-5).
 *
 * <p>The container is a bean, so it lives as long as its application context: tests with the same
 * context configuration share one database through the context cache, and a context with a
 * different configuration (the {@code no-constraint} profile) gets its own database with its own
 * set of Flyway migrations.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfig {

  private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:16-alpine");

  @Bean
  @ServiceConnection
  PostgreSQLContainer<?> postgres() {
    return new PostgreSQLContainer<>(POSTGRES_IMAGE).withDatabaseName("booking");
  }
}
