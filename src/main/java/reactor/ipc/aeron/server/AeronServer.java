package reactor.ipc.aeron.server;

import org.reactivestreams.Publisher;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.ipc.aeron.AeronConnector;
import reactor.ipc.aeron.AeronHelper;
import reactor.ipc.aeron.AeronInbound;
import reactor.ipc.aeron.AeronOptions;
import reactor.ipc.aeron.AeronOutbound;
import reactor.ipc.aeron.SignalHandler;
import reactor.util.Logger;
import reactor.util.Loggers;
import uk.co.real_logic.aeron.Subscription;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * @author Anatoly Kadyshev
 */
public final class AeronServer implements AeronConnector {

    private final AeronOptions options;

    private final String name;

    public static AeronServer create(String name, Consumer<AeronOptions> optionsConfigurer) {
        return new AeronServer(name, optionsConfigurer);
    }

    public static AeronServer create(String name) {
        return create(name, options -> {});
    }

    public static AeronServer create() {
        return create(null);
    }

    private AeronServer(String name, Consumer<AeronOptions> optionsConfigurer) {
        this.name = name == null ? "server": name;
        AeronOptions options = new AeronOptions();
        optionsConfigurer.accept(options);
        this.options = options;
    }

    @Override
    public Mono<? extends Disposable> newHandler(
            BiFunction<? super AeronInbound, ? super AeronOutbound, ? extends Publisher<Void>> ioHandler) {
        Objects.requireNonNull(ioHandler, "ioHandler");

        return Mono.create(sink -> {
            String category = name;
            AeronHelper aeronHelper = new AeronHelper(category, options);
            Subscription subscription = aeronHelper.addSubscription(options.serverChannel(), options.serverStreamId(),
                    "receiving data and requests", null);
            ServerPooler pooler = new ServerPooler(subscription,
                    new InnerSignalHandler(category, aeronHelper, ioHandler, options), name);
            pooler.initialise();

            sink.success(() -> {
                pooler.shutdown().block();
                subscription.close();
                aeronHelper.shutdown();
            });
        });
    }

    private static class InnerSignalHandler implements SignalHandler {

        private final Map<UUID, AeronServerInbound> inboundBySessionId;

        private final String category;

        private final AeronHelper aeronHelper;

        private final BiFunction<? super AeronInbound, ? super AeronOutbound, ? extends Publisher<Void>> ioHandler;

        private final AeronOptions options;

        private final Logger logger;

        public InnerSignalHandler(String category,
                                  AeronHelper aeronHelper,
                                  BiFunction<? super AeronInbound, ? super AeronOutbound, ? extends Publisher<Void>> ioHandler,
                                  AeronOptions options) {
            this.category = category;
            this.aeronHelper = aeronHelper;
            this.ioHandler = ioHandler;
            this.options = options;
            this.inboundBySessionId = new HashMap<>();
            this.logger = Loggers.getLogger(AeronServer.class + "." + category);
        }

        @Override
        public void onConnect(UUID sessionId, String channel, int streamId) {
            logger.debug("Received CONNECT for sessionId: {}, channel/streamId: {}/{}", sessionId, channel, streamId);

            AeronOutbound outbound = new AeronOutbound(category, aeronHelper, channel, streamId, sessionId, options);
            AeronServerInbound inbound = new AeronServerInbound(category);
            inboundBySessionId.put(sessionId, inbound);
            Publisher<Void> publisher = ioHandler.apply(inbound, outbound);
            Mono.from(publisher).doOnSuccess(avoid -> {
                inboundBySessionId.remove(sessionId);
                logger.debug("Closed session with sessionId: {}", sessionId);
            }).subscribe();
        }

        @Override
        public void onNext(UUID sessionId, ByteBuffer buffer) {
            logger.debug("Received NEXT - sessionId: {}, buffer: {}", sessionId, buffer);

            AeronServerInbound inbound = inboundBySessionId.get(sessionId);
            if (inbound == null) {
                //FIXME: Handle
                logger.error("Could not find inbound for sessionId: {}", sessionId);
                return;
            }

            inbound.onNext(buffer);
        }
    }
}
