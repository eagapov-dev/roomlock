package dev.roomlock.booking.infra;

import dev.roomlock.booking.Profiles;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns {@code @Scheduled} on everywhere but tests: under the {@code test} profile no task is
 * registered at all, so a test owns the clock and the triggers (rules SP-9, T-7).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile("!" + Profiles.TEST)
class SchedulingConfig {}
