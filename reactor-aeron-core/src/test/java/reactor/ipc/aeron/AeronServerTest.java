package reactor.ipc.aeron;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeron.driver.AeronResources;
import io.aeron.driver.AeronResourcesConfig;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.ReplayProcessor;
import reactor.ipc.aeron.client.AeronClient;
import reactor.ipc.aeron.client.AeronClientOptions;
import reactor.ipc.aeron.server.AeronServer;
import reactor.test.StepVerifier;

public class AeronServerTest extends BaseAeronTest {

  private static String serverChannel =
      "aeron:udp?endpoint=localhost:" + SocketUtils.findAvailableUdpPort(13000);

  private static String clientChannel =
      "aeron:udp?endpoint=localhost:" + SocketUtils.findAvailableUdpPort();

  private static int imageLivenessTimeoutSec = 1;

  private static final Consumer<AeronClientOptions> DEFAULT_CLIENT_OPTIONS =
      options -> {
        options.clientChannel(clientChannel);
        options.serverChannel(serverChannel);
      };

  private static AeronResources aeronResources;

  @BeforeAll
  static void beforeAll() {
    aeronResources =
        AeronResources.start(
            AeronResourcesConfig.builder()
                .imageLivenessTimeoutNs(TimeUnit.SECONDS.toNanos(imageLivenessTimeoutSec))
                .build());
  }

  @AfterAll
  static void afterAll() {
    Optional.ofNullable(aeronResources).ifPresent(AeronResources::dispose);
  }

  @Test
  public void testServerReceivesData() {
    ReplayProcessor<String> processor = ReplayProcessor.create();

    createServer(
        connection -> {
          connection.inbound().receive().asString().log("receive").subscribe(processor);
          return connection.onDispose();
        });

    createConnection()
        .outbound()
        .send(ByteBufferFlux.from("Hello", "world!").log("send"))
        .then()
        .subscribe();

    StepVerifier.create(processor).expectNext("Hello", "world!").thenCancel().verify();
  }

  @Test
  public void testServerDisconnectsClientsUponShutdown() throws InterruptedException {
    ReplayProcessor<ByteBuffer> processor = ReplayProcessor.create();

    OnDisposable server =
        AeronServer.create(aeronResources)
            .options(options -> options.serverChannel(serverChannel))
            .handle(
                connection -> {
                  connection.inbound().receive().log("receive").subscribe(processor);
                  return connection.onDispose();
                })
            .bind()
            .block(TIMEOUT);

    createConnection()
        .outbound()
        .send(
            Flux.range(1, 100)
                .delayElements(Duration.ofSeconds(1))
                .map(i -> AeronUtils.stringToByteBuffer("" + i))
                .log("send"))
        .then()
        .subscribe();

    processor.blockFirst();

    server.dispose();

    ThreadWatcher threadWatcher = new ThreadWatcher();

    assertTrue(threadWatcher.awaitTerminated(5000, "single-", "parallel-"));
  }

  @Test
  public void testServerDisconnectsSessionAndClientHandleUnavailableImage() throws InterruptedException {
    ReplayProcessor<ByteBuffer> processor = ReplayProcessor.create();
    CountDownLatch latch = new CountDownLatch(1);

    addDisposable(
        AeronServer.create(aeronResources)
            .options(options -> options.serverChannel(serverChannel))
            .doOnConnection(connection -> {
              connection.onDispose().doOnSuccess(aVoid -> latch.countDown()).subscribe();
              connection.inbound().receive().subscribe(processor);
            })
            .bind()
            .block(TIMEOUT));

    createServer(
        connection -> {
          connection.inbound().receive().subscribe(processor);
          return connection.onDispose();
        });

    Connection connection = createConnection();
    connection
        .outbound()
        .send(
            Flux.range(1, 100)
                .delayElements(Duration.ofSeconds(1))
                .map(i -> AeronUtils.stringToByteBuffer("" + i))
                .log("send"))
        .then()
        .subscribe();

    processor.blockFirst();

    connection.dispose();

    latch.await(imageLivenessTimeoutSec, TimeUnit.SECONDS);

    assertEquals(0, latch.getCount());
  }

  private Connection createConnection() {
    return createConnection(DEFAULT_CLIENT_OPTIONS);
  }

  private Connection createConnection(Consumer<AeronClientOptions> options) {
    Connection connection =
        AeronClient.create(aeronResources).options(options).connect().block(TIMEOUT);
    return addDisposable(connection);
  }

  private OnDisposable createServer(
      Function<? super Connection, ? extends Publisher<Void>> handler) {
    return addDisposable(
        AeronServer.create(aeronResources)
            .options(options -> options.serverChannel(serverChannel))
            .handle(handler)
            .bind()
            .block(TIMEOUT));
  }
}
