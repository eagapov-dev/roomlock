package dev.roomlock.notification.support;

import dev.roomlock.contract.v1.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Infrastructure containers for notification-service tests (rule T-5).
 *
 * <p>The containers are beans, so they live as long as their application context: tests with the
 * same context configuration share one database and one broker through the context cache.
 *
 * <p>The topics are declared here as well. {@code KafkaAdmin} creates them while the context
 * refreshes, before the listener container starts, so no test has to wait for a consumer metadata
 * refresh to discover a topic that did not exist yet.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfig {

  private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:16-alpine");

  /** The image and version of infra/docker-compose.yml, Kafka in KRaft mode. */
  private static final DockerImageName KAFKA_IMAGE = DockerImageName.parse("apache/kafka:3.9.1");

  /** Same partition count as the broker of the local stack (KAFKA_NUM_PARTITIONS). */
  private static final int PARTITIONS = 3;

  /** Where records the consumer cannot handle end up, see {@code roomlock.notifications}. */
  private static final String DEAD_LETTER_TOPIC = Topics.BOOKING_EVENTS + ".DLT";

  @Bean
  @ServiceConnection
  PostgreSQLContainer<?> postgres() {
    return new PostgreSQLContainer<>(POSTGRES_IMAGE).withDatabaseName("notification");
  }

  @Bean
  @ServiceConnection
  KafkaContainer kafka() {
    return new KafkaContainer(KAFKA_IMAGE);
  }

  @Bean
  NewTopic bookingEventsTopic() {
    return TopicBuilder.name(Topics.BOOKING_EVENTS).partitions(PARTITIONS).replicas(1).build();
  }

  @Bean
  NewTopic bookingEventsDeadLetterTopic() {
    return TopicBuilder.name(DEAD_LETTER_TOPIC).partitions(PARTITIONS).replicas(1).build();
  }
}
