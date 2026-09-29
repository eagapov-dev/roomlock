package dev.roomlock.payment.support;

import dev.roomlock.payment.Profiles;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Full application on random ports - HTTP and gRPC - against a real PostgreSQL and a real Kafka
 * (rules T-4, T-5).
 *
 * <p>One annotation keeps every integration test on the same context configuration, so they share a
 * cached context, one database and one broker. Tests are not transactional: the gRPC server and the
 * listener container commit on their own threads, and isolation comes from {@link
 * DatabaseCleaner#truncateAll()} in {@code @BeforeEach} (rule T-6).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({
  TestcontainersConfig.class,
  TestClockConfig.class,
  DatabaseCleaner.class,
  PaymentTable.class,
  PaymentGrpcClient.class,
  RefundRequestPublisher.class
})
@ActiveProfiles(Profiles.TEST)
public @interface PaymentIntegrationTest {}
