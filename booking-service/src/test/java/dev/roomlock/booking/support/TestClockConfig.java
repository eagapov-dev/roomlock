package dev.roomlock.booking.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Replaces the system clock with a {@link MutableClock} starting at {@link TestData#NOW}. */
@TestConfiguration(proxyBeanMethods = false)
public class TestClockConfig {

  @Bean
  @Primary
  MutableClock mutableClock() {
    return new MutableClock(TestData.NOW);
  }
}
