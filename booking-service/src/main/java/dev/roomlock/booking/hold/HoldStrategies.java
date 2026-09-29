package dev.roomlock.booking.hold;

import java.util.Map;
import org.springframework.stereotype.Component;

/** The strategies of the service, by name, with the configured one as the active strategy (D2). */
@Component
public class HoldStrategies {

  public HoldStrategy active() {
    throw new UnsupportedOperationException("Not implemented yet");
  }

  public HoldStrategy get(LockingStrategy strategy) {
    throw new UnsupportedOperationException("Not implemented yet");
  }

  Map<LockingStrategy, HoldStrategy> all() {
    throw new UnsupportedOperationException("Not implemented yet");
  }
}
