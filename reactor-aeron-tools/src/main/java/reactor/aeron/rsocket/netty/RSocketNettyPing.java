package reactor.aeron.rsocket.netty;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.rsocket.Frame;
import io.rsocket.Payload;
import io.rsocket.RSocket;
import io.rsocket.RSocketFactory;
import io.rsocket.transport.netty.client.TcpClientTransport;
import io.rsocket.util.ByteBufPayload;
import java.time.Duration;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.HdrHistogram.Recorder;
import reactor.aeron.Configurations;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.resources.LoopResources;
import reactor.netty.tcp.TcpClient;

public final class RSocketNettyPing {

  private static final Recorder HISTOGRAM = new Recorder(TimeUnit.SECONDS.toNanos(10), 3);

  private static final ByteBuf BUFFER =
      ByteBufAllocator.DEFAULT.buffer(Configurations.MESSAGE_LENGTH);

  static {
    Random random = new Random(System.nanoTime());
    byte[] bytes = new byte[Configurations.MESSAGE_LENGTH];
    random.nextBytes(bytes);
    BUFFER.writeBytes(bytes);
  }

  /**
   * Main runner.
   *
   * @param args program arguments.
   */
  public static void main(String... args) {

    TcpClient tcpClient =
        TcpClient.create(ConnectionProvider.newConnection())
            .runOn(LoopResources.create("client", 1, true))
            .host(Configurations.MDC_ADDRESS)
            .port(Configurations.MDC_PORT);

    RSocket client =
        RSocketFactory.connect()
            .frameDecoder(Frame::retain)
            .transport(() -> TcpClientTransport.create(tcpClient))
            .start()
            .block();

    Disposable report = startReport();

    Supplier<Payload> payloadSupplier = () -> ByteBufPayload.create(BUFFER.retainedSlice());

    Flux.range(1, (int) Configurations.NUMBER_OF_MESSAGES)
        .flatMap(
            i -> {
              long start = System.nanoTime();
              return client
                  .requestResponse(payloadSupplier.get())
                  .doOnNext(Payload::release)
                  .doFinally(
                      signalType -> {
                        long diff = System.nanoTime() - start;
                        HISTOGRAM.recordValue(diff);
                      });
            },
            64)
        .doOnError(Throwable::printStackTrace)
        .doOnTerminate(
            () -> System.out.println("Sent " + Configurations.NUMBER_OF_MESSAGES + " messages."))
        .doFinally(s -> report.dispose())
        .then()
        .block();
  }

  private static Disposable startReport() {
    return Flux.interval(
            Duration.ofSeconds(Configurations.WARMUP_REPORT_DELAY),
            Duration.ofSeconds(Configurations.REPORT_INTERVAL))
        .doOnNext(RSocketNettyPing::report)
        .doFinally(RSocketNettyPing::report)
        .subscribe();
  }

  private static void report(Object ignored) {
    System.out.println("---- PING/PONG HISTO ----");
    HISTOGRAM.getIntervalHistogram().outputPercentileDistribution(System.out, 5, 1000.0, false);
    System.out.println("---- PING/PONG HISTO ----");
  }
}