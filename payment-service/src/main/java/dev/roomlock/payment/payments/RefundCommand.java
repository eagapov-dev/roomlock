package dev.roomlock.payment.payments;

import java.util.UUID;

/**
 * A request to give the money of a booking back. The booking id is the idempotency key, so a
 * repeated command - a retried gRPC call or a redelivered {@code RefundRequested} - refunds once
 * (rule I-1).
 */
public record RefundCommand(UUID bookingId) {}
