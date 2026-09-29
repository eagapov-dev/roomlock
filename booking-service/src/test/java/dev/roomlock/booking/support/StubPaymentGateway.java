package dev.roomlock.booking.support;

import dev.roomlock.booking.payment.ChargeOrder;
import dev.roomlock.booking.payment.ChargeOutcome;
import dev.roomlock.booking.payment.PaymentGateway;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Primary;

/**
 * The payment provider of an integration test: a scripted answer and a record of every order that
 * reached the port (rule T-9).
 *
 * <p>A {@code @Primary} test component rather than a {@code @MockitoBean}, so every integration
 * test keeps the one shared application context; the real gRPC adapter is tested against a real
 * server in {@code GrpcPaymentGatewayTest} instead.
 */
@TestComponent
@Primary
public class StubPaymentGateway implements PaymentGateway {

  private final List<ChargeOrder> received = new CopyOnWriteArrayList<>();
  private final AtomicReference<Function<ChargeOrder, ChargeOutcome>> answer =
      new AtomicReference<>(approval());

  /** Forgets what was charged and goes back to approving, for {@code @BeforeEach}. */
  public void reset() {
    received.clear();
    answer.set(approval());
  }

  public void approveEverything() {
    answer.set(approval());
  }

  public void declineEverything(String reason) {
    answer.set(order -> new ChargeOutcome.Declined(reason));
  }

  /** As a provider that is unreachable or slower than the deadline looks to the flow. */
  public void beUnavailable() {
    answer.set(order -> new ChargeOutcome.Unavailable("stubbed"));
  }

  /** Every order that reached the port, in the order the calls were made. */
  public List<ChargeOrder> received() {
    return List.copyOf(received);
  }

  @Override
  public ChargeOutcome charge(ChargeOrder order) {
    received.add(order);
    return answer.get().apply(order);
  }

  private static Function<ChargeOrder, ChargeOutcome> approval() {
    return order -> new ChargeOutcome.Approved(UUID.randomUUID().toString());
  }
}
