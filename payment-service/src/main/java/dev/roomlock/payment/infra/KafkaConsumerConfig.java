package dev.roomlock.payment.infra;

import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Error handling of the listener container: one bad record must never stop the consumer or block
 * its partition.
 *
 * <p>A failure that may pass later - the database is away - is retried with a fixed backoff. An
 * {@link UnreadableEventException} never passes, so it skips the retries. Either way the record
 * ends up on {@code <topic><dead-letter-suffix>} with the reason logged, and the consumer moves on
 * to the next offset.
 *
 * <p>Spring Boot hands a single {@link CommonErrorHandler} bean to the listener container factory
 * it auto-configures, so this bean is all the wiring the container needs. The same pattern as
 * notification-service, on purpose: both services consume the same kind of contract.
 */
@Configuration(proxyBeanMethods = false)
class KafkaConsumerConfig {

  private static final Logger log = LoggerFactory.getLogger(KafkaConsumerConfig.class);

  /** Let the producer pick the partition: the dead-letter topic may have fewer of them. */
  private static final int ANY_PARTITION = -1;

  @Bean
  CommonErrorHandler refundRequestsErrorHandler(
      KafkaTemplate<?, ?> kafka, RefundProperties properties) {
    DeadLetterPublishingRecoverer deadLetters =
        new DeadLetterPublishingRecoverer(
            kafka,
            (record, exception) ->
                new TopicPartition(record.topic() + properties.deadLetterSuffix(), ANY_PARTITION));
    ConsumerRecordRecoverer recoverer =
        (record, exception) -> {
          log.error(
              "Parking record from {}-{} offset {} on the dead-letter topic: {}",
              record.topic(),
              record.partition(),
              record.offset(),
              exception.getMessage(),
              exception);
          deadLetters.accept(record, exception);
        };
    DefaultErrorHandler errorHandler =
        new DefaultErrorHandler(
            recoverer,
            new FixedBackOff(
                properties.retry().backoff().toMillis(), properties.retry().attempts()));
    errorHandler.addNotRetryableExceptions(UnreadableEventException.class);
    return errorHandler;
  }
}
