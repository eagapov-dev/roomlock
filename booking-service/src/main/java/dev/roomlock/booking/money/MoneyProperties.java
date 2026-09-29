package dev.roomlock.booking.money;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Money of this deployment.
 *
 * @param currency ISO 4217 code every price of this deployment is in; the booking table has no
 *     currency column, so it belongs to the deployment rather than to a booking. Both the events of
 *     the contract and the charge sent to payment-service carry it, which is why it lives here and
 *     not under the prefix of one of them.
 */
@ConfigurationProperties("roomlock.money")
public record MoneyProperties(String currency) {}
