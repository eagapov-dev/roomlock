package dev.roomlock.booking.support;

import dev.roomlock.booking.Profiles;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Full application on a random port against a real PostgreSQL (rules T-4, T-5).
 *
 * <p>One annotation keeps every integration test on the same context configuration, so they share a
 * cached context and one container. Tests are not transactional: isolation comes from {@link
 * DatabaseCleaner#truncateAll()} in {@code @BeforeEach} (rule T-6).
 *
 * <p>{@code @AutoConfigureObservability} undoes the default of Spring Boot tests, which is to
 * disable metrics export: without it the context gets a {@code SimpleMeterRegistry} and no {@code
 * /actuator/prometheus}, and the meters of the hold flow could not be asserted where they are
 * actually scraped. It belongs here so every integration test keeps one shared context.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability
@Import({
  TestcontainersConfig.class,
  TestClockConfig.class,
  DatabaseCleaner.class,
  BookingTable.class,
  OutboxTable.class,
  StubPaymentGateway.class
})
@ActiveProfiles(Profiles.TEST)
public @interface BookingIntegrationTest {}
