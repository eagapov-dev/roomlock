package dev.roomlock.booking.hold;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** The strategies of the service, by name, with the configured one as the active strategy (D2). */
@Component
public class HoldStrategies {

  private final Map<LockingStrategy, HoldStrategy> byStrategy;
  private final LockingStrategy configured;

  HoldStrategies(List<HoldStrategy> strategies, LockingProperties properties) {
    this.byStrategy = new EnumMap<>(LockingStrategy.class);
    strategies.forEach(strategy -> byStrategy.put(strategy.strategy(), strategy));
    this.configured = properties.strategy();
  }

  /** The strategy of {@code roomlock.locking.strategy}, the one every hold goes through. */
  public HoldStrategy active() {
    return get(configured);
  }

  public HoldStrategy get(LockingStrategy strategy) {
    HoldStrategy found = byStrategy.get(strategy);
    if (found == null) {
      throw new IllegalStateException("No hold strategy for " + strategy);
    }
    return found;
  }
}
