package dev.roomlock.notification.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.roomlock.contract.v1.BookingEvent;
import dev.roomlock.contract.v1.Topics;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaConnectionDetails;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.lang.Nullable;

/**
 * Stands in for the producer of booking-service, which does not exist yet: tests publish the JSON
 * of the contract themselves, keyed by booking id and with the {@code event-type} header.
 *
 * <p>A plain {@link KafkaProducer} on the broker of {@link TestcontainersConfig}, not a template of
 * the application context: the service under test is a consumer and its only producer is the
 * dead-letter publisher.
 */
@TestComponent
public class BookingEventPublisher implements AutoCloseable {

  /** The header the type of an event travels in, see {@link BookingEvent#type()}. */
  public static final String EVENT_TYPE_HEADER = "event-type";

  private static final long SEND_TIMEOUT_SECONDS = 30;

  private final Producer<String, String> producer;
  private final ObjectMapper json;

  public BookingEventPublisher(KafkaConnectionDetails kafka, ObjectMapper json) {
    Map<String, Object> config = new HashMap<>();
    config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
    config.put(ProducerConfig.ACKS_CONFIG, "all");
    this.producer = new KafkaProducer<>(config, new StringSerializer(), new StringSerializer());
    this.json = json;
  }

  /** Publishes the event as the contract describes it and waits for the broker to acknowledge. */
  public void publish(BookingEvent event) {
    publishRaw(event.bookingId().toString(), payloadOf(event), event.type());
  }

  /**
   * Publishes a payload as it is, for records the contract does not allow: a malformed body, an
   * unknown type, or no {@code event-type} header at all when {@code eventType} is {@code null}.
   */
  public void publishRaw(String key, String payload, @Nullable String eventType) {
    ProducerRecord<String, String> record =
        new ProducerRecord<>(Topics.BOOKING_EVENTS, key, payload);
    if (eventType != null) {
      record.headers().add(EVENT_TYPE_HEADER, eventType.getBytes(StandardCharsets.UTF_8));
    }
    send(record);
  }

  @Override
  public void close() {
    producer.close();
  }

  private void send(ProducerRecord<String, String> record) {
    try {
      producer.send(record).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while publishing a booking event", interrupted);
    } catch (ExecutionException | TimeoutException failed) {
      throw new IllegalStateException("Could not publish a booking event", failed);
    }
  }

  private String payloadOf(BookingEvent event) {
    try {
      return json.writeValueAsString(event);
    } catch (JsonProcessingException notSerializable) {
      throw new IllegalStateException("Could not serialize " + event, notSerializable);
    }
  }
}
