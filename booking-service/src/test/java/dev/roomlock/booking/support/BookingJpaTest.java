package dev.roomlock.booking.support;

import dev.roomlock.booking.Profiles;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Repository/schema slice on a real PostgreSQL (rules T-4, T-5, T-6).
 *
 * <p>One annotation keeps every JPA slice test on the same context configuration, so they share a
 * cached context and one container. Tests are not transactional: each statement commits, and
 * isolation comes from {@link DatabaseCleaner#truncateAll()} in {@code @BeforeEach}. A test class
 * may add its own {@code @ActiveProfiles}, which replaces the default one.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
  TestcontainersConfig.class,
  TestClockConfig.class,
  DatabaseCleaner.class,
  BookingTable.class
})
@ActiveProfiles(Profiles.TEST)
public @interface BookingJpaTest {}
