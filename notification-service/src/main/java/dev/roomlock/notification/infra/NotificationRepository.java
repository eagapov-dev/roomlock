package dev.roomlock.notification.infra;

import dev.roomlock.notification.domain.Notification;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Recorded deliveries. */
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

  /** Whether the event was already handled, which is what makes a redelivery a no-op. */
  boolean existsByEventId(UUID eventId);
}
