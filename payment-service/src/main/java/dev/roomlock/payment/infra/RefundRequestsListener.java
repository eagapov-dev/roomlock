package dev.roomlock.payment.infra;

import dev.roomlock.contract.v1.RefundRequested;
import dev.roomlock.payment.domain.Payment;
import dev.roomlock.payment.payments.PaymentService;
import dev.roomlock.payment.payments.RefundCommand;
import dev.roomlock.payment.payments.RefundResult;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes the refund requests topic, keyed by booking id, and refunds through the same use case
 * the gRPC {@code Refund} call goes through - so a redelivered event refunds once, for the same
 * reason a retried call does (rule I-1).
 *
 * <p>Nothing is caught here on purpose: a failure belongs to the container's error handler, which
 * retries what may succeed later and parks what never will (see {@link KafkaConsumerConfig}).
 */
@Component
class RefundRequestsListener {

  private static final Logger log = LoggerFactory.getLogger(RefundRequestsListener.class);

  private final RefundEventReader reader;
  private final PaymentService payments;

  RefundRequestsListener(RefundEventReader reader, PaymentService payments) {
    this.reader = reader;
    this.payments = payments;
  }

  @KafkaListener(topics = "${roomlock.payment.refunds.topic}")
  void onRefundRequested(ConsumerRecord<String, String> record) {
    RefundRequested event = reader.read(record);
    log.debug(
        "Handling {} {} from {}-{} offset {}",
        event.type(),
        event.eventId(),
        record.topic(),
        record.partition(),
        record.offset());
    log(event, payments.refund(new RefundCommand(event.bookingId())));
  }

  private static void log(RefundRequested event, RefundResult result) {
    switch (result) {
      case RefundResult.Refunded(Payment payment) ->
          log.info(
              "Event {} refunded payment {} of booking {}",
              event.eventId(),
              payment.getId(),
              event.bookingId());
      case RefundResult.AlreadyRefunded(Payment payment) ->
          log.info(
              "Event {} is a redelivery: payment {} of booking {} was already refunded",
              event.eventId(),
              payment.getId(),
              event.bookingId());
      case RefundResult.NotApproved ignored ->
          log.warn(
              "Event {} asks for the money of booking {} back, but its charge was declined",
              event.eventId(),
              event.bookingId());
      case RefundResult.NotCharged(var bookingId) ->
          log.warn(
              "Event {} asks for the money of booking {} back, but it was never charged",
              event.eventId(),
              bookingId);
    }
  }
}
