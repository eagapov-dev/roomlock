package dev.roomlock.booking.outbox;

import static dev.roomlock.booking.support.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import dev.roomlock.booking.domain.OutboxEvent;
import dev.roomlock.contract.v1.BookingEvent;
import dev.roomlock.contract.v1.Topics;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The transport of a published event (task 4.2): the topic its type belongs to, the booking as the
 * key so the events of one booking keep their order, and the type in the header a consumer reads.
 *
 * <p>A real broker, but no application context: the adapter is all that is under test here.
 */
@Testcontainers
class KafkaEventSenderTest {

  /** The image and version of infra/docker-compose.yml, Kafka in KRaft mode. */
  private static final DockerImageName KAFKA_IMAGE = DockerImageName.parse("apache/kafka:3.9.1");

  private static final Duration POLL = Duration.ofSeconds(10);

  @Container static final KafkaContainer KAFKA = new KafkaContainer(KAFKA_IMAGE);

  private static KafkaTemplate<String, String> template;

  private final KafkaEventSender sender = new KafkaEventSender(template());

  @AfterAll
  static void closeTemplate() {
    if (template != null) {
      template.destroy();
      template = null;
    }
  }

  @Test
  void shouldSendBookingEventToTheBookingTopicWhenItIsPublished() {
    // given
    OutboxEvent event = pending("BookingHeld");

    // when
    sender.send(event);

    // then
    ConsumerRecord<String, String> published = readOne(Topics.BOOKING_EVENTS);
    assertThat(published.key()).isEqualTo(event.getAggregateId().toString());
    assertThat(published.value()).isEqualTo(event.getPayload());
    assertThat(headerOf(published)).isEqualTo("BookingHeld");
  }

  @Test
  void shouldSendRefundRequestToTheRefundTopicWhenItIsPublished() {
    // given
    OutboxEvent event = pending("RefundRequested");

    // when
    sender.send(event);

    // then
    ConsumerRecord<String, String> published = readOne(Topics.REFUND_REQUESTS);
    assertThat(published.key()).isEqualTo(event.getAggregateId().toString());
    assertThat(headerOf(published)).isEqualTo("RefundRequested");
  }

  @Test
  void shouldFailWhenTheTypeBelongsToNoTopicOfTheContract() {
    // given
    OutboxEvent event = pending("BookingForgotten");

    // when
    // then
    assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> sender.send(event));
  }

  private static OutboxEvent pending(String type) {
    UUID bookingId = UUID.randomUUID();
    String payload =
        "{\"eventId\":\"" + UUID.randomUUID() + "\",\"bookingId\":\"" + bookingId + "\"}";
    return OutboxEvent.pending(bookingId, type, payload, NOW);
  }

  private static KafkaTemplate<String, String> template() {
    if (template == null) {
      Map<String, Object> config = new HashMap<>();
      config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
      config.put(ProducerConfig.ACKS_CONFIG, "all");
      ProducerFactory<String, String> producers =
          new DefaultKafkaProducerFactory<>(config, new StringSerializer(), new StringSerializer());
      template = new KafkaTemplate<>(producers);
    }
    return template;
  }

  private static String headerOf(ConsumerRecord<String, String> record) {
    Header header = record.headers().lastHeader(BookingEvent.EVENT_TYPE_HEADER);
    return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
  }

  /** Reads the single record of the topic, from its beginning. */
  private static ConsumerRecord<String, String> readOne(String topic) {
    Map<String, Object> config = new HashMap<>();
    config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    config.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
    config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    try (KafkaConsumer<String, String> consumer =
        new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
      consumer.subscribe(List.of(topic));
      ConsumerRecords<String, String> records = consumer.poll(POLL);
      assertThat(records.count()).isEqualTo(1);
      return records.iterator().next();
    }
  }
}
