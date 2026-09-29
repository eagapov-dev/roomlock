package dev.roomlock.notification.domain;

import java.util.UUID;

/**
 * One message ready to go out, as a sender receives it: the text is already rendered, so no adapter
 * has to know anything about booking events.
 */
public record GuestNotification(
    UUID bookingId, UUID guestId, NotificationKind kind, String subject, String body) {}
