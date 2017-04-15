package reactor.ipc.aeron;


import reactor.core.publisher.Mono;
import uk.co.real_logic.aeron.FragmentAssembler;
import uk.co.real_logic.aeron.Subscription;
import uk.co.real_logic.aeron.logbuffer.FragmentHandler;
import uk.co.real_logic.aeron.logbuffer.Header;
import uk.co.real_logic.agrona.DirectBuffer;
import uk.co.real_logic.agrona.concurrent.BackoffIdleStrategy;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author Anatoly Kadyshev
 */
public class Pooler implements Runnable {

    //FIXME: Use something from reactor
    private final ExecutorService executor;

    private volatile boolean isRunning;

    private Subscription subscription;

    private FragmentHandler delegateHandler;

    public Pooler(Subscription subscription, FragmentHandler delegateHandler, String name) {
        this.subscription = subscription;
        this.delegateHandler = delegateHandler;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, name + "-[pooler]");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void initialise() {
        isRunning = true;
        executor.submit(this);
    }

    public Mono<Void> shutdown() {
        return Mono.create(sink -> {
            isRunning = false;
            AtomicBoolean shouldRetry = new AtomicBoolean(true);
            sink.setCancellation(() -> shouldRetry.set(false));
            executor.shutdown();
            try {
                while (shouldRetry.get()) {
                    boolean wasShutdown = executor.awaitTermination(1, TimeUnit.SECONDS);
                    if (wasShutdown) {
                        break;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            sink.success();
        });
    }

    @Override
    public void run() {
        BackoffIdleStrategy idleStrategy = AeronUtils.newBackoffIdleStrategy();
        FragmentHandler fragmentHandler = new FragmentAssembler(new FragmentHandler() {
            @Override
            public void onFragment(DirectBuffer buffer, int offset, int length, Header header) {
                delegateHandler.onFragment(buffer, offset, length, header);
            }
        });
        while (isRunning) {
            int nReceived = subscription.poll(fragmentHandler, 1);
            idleStrategy.idle(nReceived);
        }
    }

}
