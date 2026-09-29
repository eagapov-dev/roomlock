package dev.roomlock.booking.support;

import dev.roomlock.payment.api.v1.ChargeRequest;
import dev.roomlock.payment.api.v1.ChargeResponse;
import dev.roomlock.payment.api.v1.PaymentServiceGrpc;
import dev.roomlock.payment.api.v1.PaymentServiceGrpc.PaymentServiceBlockingStub;
import dev.roomlock.payment.api.v1.RefundRequest;
import dev.roomlock.payment.api.v1.RefundResponse;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.InsecureServerCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A real gRPC server serving the {@code roomlock.payment.v1.PaymentService} contract on a free
 * local port, with a scripted answer to every charge (rule T-4).
 *
 * <p>What the adapter under test does is the transport, the generated stubs and the deadline, so a
 * mocked stub would test nothing. payment-service is not on this classpath and must not be: only
 * the contract of {@code :payment-api} is shared, which is exactly what an adapter test should be
 * pinned to.
 */
public final class StubPaymentServer implements AutoCloseable {

  /** What the server does with a charge it receives. */
  public sealed interface Answer {

    /** Answers with this response. */
    record Respond(ChargeResponse response) implements Answer {}

    /** Fails the call with this status, as a provider that refuses the call itself would. */
    record Fail(Status status) implements Answer {}

    /**
     * Never answers at all, so the client's deadline is what ends the call. Nothing sleeps: the
     * observer is simply dropped (rule T-7).
     */
    record Hang() implements Answer {}
  }

  private final List<ChargeRequest> received = new CopyOnWriteArrayList<>();
  private final List<Answer> answers;
  private final Server server;
  private final ManagedChannel channel;
  private final PaymentServiceBlockingStub stub;

  private StubPaymentServer(List<Answer> answers) {
    this.answers = answers;
    this.server = start();
    this.channel =
        Grpc.newChannelBuilderForAddress(
                "localhost", server.getPort(), InsecureChannelCredentials.create())
            .build();
    this.stub = PaymentServiceGrpc.newBlockingStub(channel);
  }

  /**
   * Starts a server that answers the scripted answers in order, repeating the last one once the
   * script runs out: a test that does not care how often it is called does not have to say.
   */
  public static StubPaymentServer answering(Answer... answers) {
    if (answers.length == 0) {
      throw new IllegalArgumentException("A scripted server needs at least one answer");
    }
    return new StubPaymentServer(List.of(answers));
  }

  /** Approves every charge with a fresh payment id. */
  public static StubPaymentServer approving() {
    return answering(new Answer.Respond(approved()));
  }

  public static ChargeResponse approved() {
    return ChargeResponse.newBuilder()
        .setPaymentId(UUID.randomUUID().toString())
        .setStatus(ChargeResponse.Status.APPROVED)
        .build();
  }

  public static ChargeResponse declined(String reason) {
    return ChargeResponse.newBuilder()
        .setPaymentId(UUID.randomUUID().toString())
        .setStatus(ChargeResponse.Status.DECLINED)
        .setDeclineReason(reason)
        .build();
  }

  /** The stub a gateway under test is built with. */
  public PaymentServiceBlockingStub stub() {
    return stub;
  }

  /** Every charge the server was asked for, in the order the calls arrived. */
  public List<ChargeRequest> received() {
    return List.copyOf(received);
  }

  @Override
  public void close() {
    channel.shutdownNow();
    server.shutdownNow();
  }

  private Server start() {
    try {
      return Grpc.newServerBuilderForPort(0, InsecureServerCredentials.create())
          .addService(new ScriptedPaymentService())
          .build()
          .start();
    } catch (IOException notStarted) {
      throw new UncheckedIOException("The stub payment server did not start", notStarted);
    }
  }

  private final class ScriptedPaymentService extends PaymentServiceGrpc.PaymentServiceImplBase {

    @Override
    public void charge(ChargeRequest request, StreamObserver<ChargeResponse> observer) {
      Answer answer = answers.get(Math.min(received.size(), answers.size() - 1));
      received.add(request);
      switch (answer) {
        case Answer.Respond(ChargeResponse response) -> {
          observer.onNext(response);
          observer.onCompleted();
        }
        case Answer.Fail(Status status) -> observer.onError(status.asRuntimeException());
        case Answer.Hang ignored -> {
          // The deadline of the client ends this call.
        }
      }
    }

    /** Part of the contract and never called: a refund travels as an event, not as a call. */
    @Override
    public void refund(RefundRequest request, StreamObserver<RefundResponse> observer) {
      observer.onError(Status.UNIMPLEMENTED.asRuntimeException());
    }
  }
}
