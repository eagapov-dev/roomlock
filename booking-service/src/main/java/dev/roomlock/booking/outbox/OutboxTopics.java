package dev.roomlock.booking.outbox;

import dev.roomlock.contract.v1.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * The topics of the contract, declared by the service that produces them.
 *
 * <p>Without this the first event of a topic waits for the broker to create it lazily, which costs
 * the producer up to {@code max.block.ms} - a minute by default - and holds up the whole sweep
 * while it waits. Worse, a broker with automatic creation switched off, as a production one usually
 * has, would refuse that event for good.
 *
 * <p>The counts match the broker of {@code infra/docker-compose.yml}; a real cluster provisions its
 * topics outside the application, and {@code KafkaAdmin} leaves an existing topic alone.
 */
@Configuration(proxyBeanMethods = false)
class OutboxTopics {

  private static final int PARTITIONS = 3;
  private static final int REPLICAS = 1;

  @Bean
  NewTopic bookingEventsTopic() {
    return TopicBuilder.name(Topics.BOOKING_EVENTS)
        .partitions(PARTITIONS)
        .replicas(REPLICAS)
        .build();
  }

  @Bean
  NewTopic refundRequestsTopic() {
    return TopicBuilder.name(Topics.REFUND_REQUESTS)
        .partitions(PARTITIONS)
        .replicas(REPLICAS)
        .build();
  }
}
