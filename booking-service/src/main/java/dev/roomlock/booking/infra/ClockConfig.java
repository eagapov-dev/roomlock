package dev.roomlock.booking.infra;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The only source of time in the service (rule SP-12). */
@Configuration(proxyBeanMethods = false)
final class ClockConfig {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
