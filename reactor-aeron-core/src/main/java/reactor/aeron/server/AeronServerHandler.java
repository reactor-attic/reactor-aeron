package reactor.aeron.server;

import io.aeron.Image;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.agrona.DirectBuffer;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.aeron.AeronOptions;
import reactor.aeron.AeronResources;
import reactor.aeron.Connection;
import reactor.aeron.DefaultAeronConnection;
import reactor.aeron.DefaultAeronInbound;
import reactor.aeron.DefaultAeronOutbound;
import reactor.aeron.MessagePublication;
import reactor.aeron.OnDisposable;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoProcessor;

/**
 * Full-duplex aeron server handler. Schematically can be described as:
 *
 * <pre>
 * Server
 * serverPort->inbound->Sub(endpoint, acceptor[onImageAvailable, onImageUnavailbe])
 * + onImageAvailable(Image)
 * sessionId->inbound->EmitterPocessor
 * serverControlPort->outbound->MDC(sessionId)->Pub(control-endpoint, sessionId)
 * </pre>
 */
final class AeronServerHandler implements FragmentHandler, OnDisposable {

  private static final Logger logger = LoggerFactory.getLogger(AeronServerHandler.class);

  private final AeronOptions options;
  private final AeronResources resources;
  private final Function<? super Connection, ? extends Publisher<Void>> handler;

  // TODO think of more performant concurrent hashmap
  private final Map<Integer, Connection> connections = new ConcurrentHashMap<>(32);

  private final MonoProcessor<Void> dispose = MonoProcessor.create();
  private final MonoProcessor<Void> onDispose = MonoProcessor.create();

  AeronServerHandler(AeronOptions options) {
    this.options = options;
    this.resources = options.resources();
    this.handler = options.handler();

    dispose
        .then(doDispose())
        .doFinally(s -> onDispose.onComplete())
        .subscribe(
            null,
            th -> logger.warn("Disposed aeron server handler with error: {}", th.toString()),
            () -> logger.debug("Disposed aeron server handler"));
  }

  Mono<OnDisposable> start() {
    return Mono.defer(
        () -> {
          // Sub(endpoint{address:serverPort})
          String inboundChannel = options.inboundUri().asString();

          logger.debug("Starting aeron server handler on: {}", inboundChannel);

          return resources
              .subscription(
                  inboundChannel,
                  this, /*fragmentHandler*/
                  this::onImageAvailable, /*setup new session*/
                  this::onImageUnavailable /*remove and dispose session*/)
              .thenReturn(this)
              .doOnSuccess(
                  handler -> logger.debug("Started aeron server handler on: {}", inboundChannel))
              .doOnError(
                  ex -> {
                    logger.error("Failed to start aeron server handler on: {}", inboundChannel);
                    dispose();
                  });
        });
  }

  /**
   * Inner implementation of aeron's {@link FragmentHandler}. Sits on the server inbound channel and
   * serves all incoming sessions. By {@link Header#sessionId()} corresponding {@link Connection} is
   * being found and message passed there.
   */
  @Override
  public void onFragment(DirectBuffer buffer, int offset, int length, Header header) {
    int sessionId = header.sessionId();
    Connection connection = connections.get(sessionId);

    if (connection == null) {
      logger.warn(
          "{}: received message but server connection not found (total connections: {})",
          Integer.toHexString(sessionId),
          connections.size());
      return;
    }

    DefaultAeronInbound inbound = (DefaultAeronInbound) connection.inbound();
    inbound.onFragment(buffer, offset, length, header);
  }

  /**
   * Setting up new {@link Connection} identified by {@link Image#sessionId()}. Specifically creates
   * message publication (aeron {@link io.aeron.Publication} underneath) with control-endpoint,
   * control-mode and given sessionId. Essentially creates server side MDC for concrete sessionId;
   * think of this as <i>server-side-individual-MDC</i>.
   *
   * @param image source image
   */
  private void onImageAvailable(Image image) {
    int sessionId = image.sessionId();
    // Pub(control-endpoint{address:serverControlPort}, sessionId)->MDC(sessionId)
    String outboundChannel = options.outboundUri().sessionId(sessionId).asString();

    // not expecting following condition be true at normal circumstances; passing it would mean
    // aeron changed contract/semantic around sessionId
    if (connections.containsKey(sessionId)) {
      logger.error(
          "{}: server connection already exists: {}",
          Integer.toHexString(sessionId),
          outboundChannel);
      return;
    }

    logger.debug(
        "{}: creating server connection: {}", Integer.toHexString(sessionId), outboundChannel);

    resources
        .publication(outboundChannel, options)
        .flatMap(MessagePublication::ensureConnected)
        .map(
            publication -> {
              DefaultAeronInbound inbound = new DefaultAeronInbound();
              DefaultAeronOutbound outbound = new DefaultAeronOutbound(publication);
              return new DefaultAeronConnection(sessionId, inbound, outbound, publication);
            })
        .doOnSuccess(connection -> setupConnection(sessionId, connection))
        .subscribe(
            null,
            ex ->
                logger.warn(
                    "{}: failed to create server outbound: {}, cause: {}",
                    Integer.toHexString(sessionId),
                    outboundChannel,
                    ex.toString()),
            () ->
                logger.debug(
                    "{}: created server connection: {}",
                    Integer.toHexString(sessionId),
                    outboundChannel));
  }

  /**
   * Removes {@link Connection} (and then disposes it) by incoming {@link Image#sessionId()}. See
   * also {@link Connection#dispose()}.
   *
   * @param image source image
   */
  private void onImageUnavailable(Image image) {
    int sessionId = image.sessionId();
    Connection connection = connections.remove(sessionId);

    if (connection != null) {
      logger.debug("{}: server inbound became unavailable", Integer.toHexString(sessionId));
      connection.dispose();
      logger.debug(
          "{}: removed and disposed server connection: {}",
          Integer.toHexString(sessionId),
          connection);
    } else {
      logger.debug(
          "{}: attempt to remove server connection but it wasn't found (total connections: {})",
          Integer.toHexString(sessionId),
          connections.size());
    }
  }

  private void setupConnection(int sessionId, DefaultAeronConnection connection) {
    // store
    connections.put(sessionId, connection);

    // register cleanup hook
    connection
        .onDispose()
        .doFinally(s -> connections.remove(sessionId))
        .subscribe(
            null,
            th -> {
              // no-op
            });

    if (handler == null) {
      logger.warn("{}: handler function is not specified", Integer.toHexString(sessionId));
      return;
    }

    try {
      if (!connection.isDisposed()) {
        handler.apply(connection).subscribe(connection.disposeSubscriber());
      }
    } catch (Exception ex) {
      logger.error(
          "{}: unexpected exception occurred on handler.apply(), cause: ",
          Integer.toHexString(sessionId),
          ex);
      connection.dispose();
      throw Exceptions.propagate(ex);
    }
  }

  @Override
  public void dispose() {
    dispose.onComplete();
  }

  @Override
  public boolean isDisposed() {
    return onDispose.isDisposed();
  }

  @Override
  public Mono<Void> onDispose() {
    return onDispose;
  }

  private Mono<Void> doDispose() {
    return Mono.defer(
        () -> {
          logger.debug("Aeron server handler is disposing");
          return Mono.whenDelayError(
                  connections
                      .values()
                      .stream()
                      .peek(Connection::dispose)
                      .map(Connection::onDispose)
                      .collect(Collectors.toList()))
              .doFinally(s -> connections.clear());
        });
  }

  @Override
  public String toString() {
    return "AeronServerHandler0x" + Integer.toHexString(System.identityHashCode(this));
  }
}
