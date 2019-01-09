package reactor.aeron.client;

import java.util.function.Function;
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
import reactor.aeron.MessageSubscription;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoProcessor;

/**
 * Full-duplex aeron client connector. Schematically can be described as:
 *
 * <pre>
 * Client
 * serverPort->outbound->Pub(endpoint, sessionId)
 * serverControlPort->inbound->MDC(sessionId)->Sub(control-endpoint, sessionId)</pre>
 */
final class AeronClientConnector {

  private static final Logger logger = LoggerFactory.getLogger(AeronClientConnector.class);

  private final AeronOptions options;
  private final AeronResources resources;
  private final Function<? super Connection, ? extends Publisher<Void>> handler;

  AeronClientConnector(AeronOptions options) {
    this.options = options;
    this.resources = options.resources();
    this.handler = options.handler();
  }

  /**
   * Creates and setting up {@link Connection} object and everyting around it.
   *
   * @return mono result
   */
  Mono<Connection> start() {
    return Mono.defer(
        () -> {
          // outbound->Pub(endpoint, sessionId)
          String outboundChannel = options.outboundUri().asString();

          return resources
              .publication(outboundChannel, options)
              // TODO here problem possible since sessionId is not globally unique; need to
              //  retry few times if connection wasn't succeeeded
              .flatMap(MessagePublication::ensureConnected)
              .flatMap(
                  publication -> {
                    final DefaultAeronInbound inbound = new DefaultAeronInbound();
                    final DefaultAeronOutbound outbound = new DefaultAeronOutbound(publication);

                    // inbound->MDC(sessionId)->Sub(control-endpoint, sessionId)
                    int sessionId = publication.sessionId();
                    String inboundChannel = options.inboundUri().sessionId(sessionId).asString();
                    logger.debug("{}: creating client connection", Integer.toHexString(sessionId));

                    // setup cleanup hook to use it onwards
                    MonoProcessor<Void> inboundUnavailable = MonoProcessor.create();

                    return resources
                        .subscription(
                            inboundChannel,
                            options,
                            inbound,
                            image ->
                                logger.debug(
                                    "{}: created client inbound", Integer.toHexString(sessionId)),
                            image -> {
                              logger.debug(
                                  "{}: client inbound became unavaliable",
                                  Integer.toHexString(sessionId));
                              inboundUnavailable.onComplete();
                            })
                        .flatMap(MessageSubscription::ensureConnected)
                        .doOnError(
                            th -> {
                              logger.warn(
                                  "{}: failed to create client inbound, cause: {}",
                                  Integer.toHexString(sessionId),
                                  th.toString());
                              // dispose outbound resource
                              publication.dispose();
                            })
                        .map(
                            subscription ->
                                new DefaultAeronConnection(
                                    sessionId, inbound, outbound, subscription, publication))
                        .flatMap(
                            connection -> connection.start(sessionId, handler, inboundUnavailable))
                        .doOnSuccess(
                            connection ->
                                logger.debug(
                                    "{}: created client connection: {}",
                                    Integer.toHexString(sessionId),
                                    inboundChannel));
                  });
        });
  }
}
