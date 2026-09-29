package dev.roomlock.payment.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.roomlock.contract.v1.BookingEvent;
import dev.roomlock.contract.v1.RefundRequested;
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
public class RefundRequestPublisher implements AutoCloseable {

  private static final long SEND_TIMEOUT_SECONDS = 30;

  private final Producer<String, String> producer;
  private final ObjectMapper json;

  public RefundRequestPublisher(KafkaConnectionDetails kafka, ObjectMapper json) {
    Map<String, Object> config = new HashMap<>();
    config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
    config.put(ProducerConfig.ACKS_CONFIG, "all");
    this.producer = new KafkaProducer<>(config, new StringSerializer(), new StringSerializer());
    this.json = json;
  }

  /** Publishes the event as the contract describes it and waits for the broker to acknowledge. */
  public void publish(RefundRequested event) {
    publishAs(event.bookingId().toString(), event);
  }

  /**
   * Publishes the event under a key of the caller's choosing. The key picks the partition, so an
   * event published under the key of another booking is handled after the events of that booking:
   * that is how a test knows a record it does not expect anything from was already consumed.
   */
  public void publishAs(String key, RefundRequested event) {
    publishRaw(key, payloadOf(event), event.type());
  }

  /**
   * Publishes a payload as it is, for records the contract does not allow: a malformed body, an
   * unknown type, or no {@code event-type} header at all when {@code eventType} is {@code null}.
   */
  public void publishRaw(String key, String payload, @Nullable String eventType) {
    ProducerRecord<String, String> record =
        new ProducerRecord<>(Topics.REFUND_REQUESTS, key, payload);
    if (eventType != null) {
      record
          .headers()
          .add(BookingEvent.EVENT_TYPE_HEADER, eventType.getBytes(StandardCharsets.UTF_8));
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
      throw new IllegalStateException("Interrupted while publishing a refund request", interrupted);
    } catch (ExecutionException | TimeoutException failed) {
      throw new IllegalStateException("Could not publish a refund request", failed);
    }
  }

  private String payloadOf(RefundRequested event) {
    try {
      return json.writeValueAsString(event);
    } catch (JsonProcessingException notSerializable) {
      throw new IllegalStateException("Could not serialize " + event, notSerializable);
    }
  }
}
