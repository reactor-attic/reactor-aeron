package reactor.aeron;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoProcessor;
import reactor.core.publisher.ReplayProcessor;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

class AeronWriteSequencerTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(2);

  private static final int PREFETCH = 32;
  private static final int FLUX_REQUESTS = PREFETCH * 4;

  private AeronWriteSequencer aeronWriteSequencer;
  private MessagePublication messagePublication;

  @BeforeEach
  void setUp() {
    this.messagePublication = Mockito.mock(MessagePublication.class);
    this.aeronWriteSequencer = new AeronWriteSequencer(messagePublication);
  }

  @Test
  void testWriteMonoRequestInPublicationWhichIsAlreadyDisposed() {
    // the Publication is already disposed
    Mockito.when(messagePublication.onDispose()).thenReturn(Mono.empty());
    // long wait task
    Mockito.when(messagePublication.enqueue(Mockito.any())).thenReturn(Mono.never());

    Mono<ByteBuffer> request = Mono.just(AeronUtils.stringToByteBuffer("test"));

    StepVerifier.create(aeronWriteSequencer.write(request))
        .expectErrorSatisfies(
            actual -> {
              RuntimeException expected = AeronExceptions.failWithMessagePublicationUnavailable();
              assertEquals(expected.getClass(), actual.getClass());
              assertEquals(expected.getMessage(), actual.getMessage());
            })
        .verify(TIMEOUT);
  }

  @Test
  void testWriteMonoEmptyInPublicationWhichIsAlreadyDisposed() {
    // the Publication is already disposed
    Mockito.when(messagePublication.onDispose()).thenReturn(Mono.empty());

    Mono<ByteBuffer> request = Mono.empty();

    StepVerifier.create(aeronWriteSequencer.write(request))
        .expectErrorSatisfies(
            actual -> {
              RuntimeException expected = AeronExceptions.failWithMessagePublicationUnavailable();
              assertEquals(expected.getClass(), actual.getClass());
              assertEquals(expected.getMessage(), actual.getMessage());
            })
        .verify(TIMEOUT);
  }

  @Test
  void testWriteMonoRequestInPublicationWhichRejectsIncomingRequestsBySomeReasons() {
    // the Publication is ready
    Mockito.when(messagePublication.onDispose()).thenReturn(Mono.never());
    // publication rejects incoming requests
    RuntimeException expected = new RuntimeException("some reasons");
    Mockito.when(messagePublication.enqueue(Mockito.any())).thenReturn(Mono.error(expected));

    Mono<ByteBuffer> request = Mono.just(AeronUtils.stringToByteBuffer("test"));

    StepVerifier.create(aeronWriteSequencer.write(request))
        .expectErrorSatisfies(
            actual -> {
              // workaround to get cause
              assertEquals("Failed to publish signal", actual.getMessage());
              assertNotNull(actual.getCause());

              assertEquals(expected.getClass(), actual.getCause().getClass());
              assertEquals(expected.getMessage(), actual.getCause().getMessage());
            })
        .verify(TIMEOUT);
  }

  @Test
  void testWriteMonoEmptyInReadyPublication() {
    // the Publication is ready
    Mockito.when(messagePublication.onDispose()).thenReturn(Mono.never());

    Mono<ByteBuffer> request = Mono.empty();

    StepVerifier.create(aeronWriteSequencer.write(request)).expectComplete().verify(TIMEOUT);
  }

  @Test
  void testWriteMonoNeverInReadyPublicationAndThenCancelResultSubscription() {
    // the Publication is ready
    Mockito.when(messagePublication.onDispose()).thenReturn(Mono.never());

    // mono never request
    MonoProcessor<Throwable> latch = MonoProcessor.create();
    Mono<ByteBuffer> request =
        Mono.<ByteBuffer>never()
            .doOnNext(item -> latch.onError(fail("it was emitted an item")))
            .doOnError(ex -> latch.onError(fail("it was emitted ex: ", ex)))
            .doOnTerminate(() -> latch.onError(fail("it was emitted the completion signal")))
            .doOnCancel(latch::onComplete);

    StepVerifier.create(aeronWriteSequencer.write(request))
        .thenAwait(TIMEOUT.dividedBy(2))
        // cancel the subscription as soon as pause is finished
        .thenCancel()
        .verify(TIMEOUT);

    assertNull(latch.block(TIMEOUT));
  }

  @Test
  void testWriteMonoDelayRequestInReadyPublicationAndThenCancelResultSubscription() {
    // the Publication is ready
    Mockito.when(messagePublication.onDispose()).thenReturn(Mono.never());

    // mono request with delay
    MonoProcessor<Throwable> latch = MonoProcessor.create();
    Mono<ByteBuffer> request =
        Mono.delay(TIMEOUT)
            .map(i -> AeronUtils.stringToByteBuffer("test"))
            .doOnNext(item -> latch.onError(fail("it was emitted an item")))
            .doOnError(ex -> latch.onError(fail("it was emitted ex: ", ex)))
            .doOnTerminate(() -> latch.onError(fail("it was emitted the completion signal")))
            .doOnCancel(latch::onComplete);

    StepVerifier.create(aeronWriteSequencer.write(request))
        .thenAwait(TIMEOUT.dividedBy(2))
        // cancel the subscription as soon as pause is finished
        .thenCancel()
        .verify(TIMEOUT);

    assertNull(latch.block(TIMEOUT));
  }

  @Test
  void testWriteMonoRequestInPublicationAndWhileWaitForSendingWillCancelSubscription() {
    // the Publication is ready
    Mockito.when(messagePublication.onDispose()).thenReturn(Mono.never());

    // long wait task
    MonoProcessor<Throwable> latch = MonoProcessor.create();
    Mockito.when(messagePublication.enqueue(Mockito.any()))
        .thenReturn(
            Mono.<Void>never()
                .log("response")
                .doOnNext(item -> latch.onError(fail("it was emitted an item")))
                .doOnError(ex -> latch.onError(fail("it was emitted ex: ", ex)))
                .doOnTerminate(() -> latch.onError(fail("it was emitted the completion signal")))
                .doOnCancel(latch::onComplete));

    // mono request
    Mono<ByteBuffer> request = Mono.just(AeronUtils.stringToByteBuffer("test"));

    StepVerifier.create(aeronWriteSequencer.write(request))
        .thenAwait(TIMEOUT.dividedBy(2))
        // cancel the subscription as soon as pause is finished
        .thenCancel()
        .verify(TIMEOUT);

    assertNull(latch.block(TIMEOUT));
  }

  @Test
  void testWriteMonoRequestInPublicationAndWaitForSuccessfulSending() {
    // the Publication is ready
    Mockito.when(messagePublication.onDispose()).thenReturn(Mono.never());
    // long wait task
    Duration delayForFinishEnqueueTask = TIMEOUT.dividedBy(2);
    Mockito.when(messagePublication.enqueue(Mockito.any()))
        .thenReturn(Mono.delay(delayForFinishEnqueueTask).then());

    // mono request
    Mono<ByteBuffer> request = Mono.just(AeronUtils.stringToByteBuffer("test"));

    long start = System.nanoTime();
    StepVerifier.create(aeronWriteSequencer.write(request)).expectComplete().verify(TIMEOUT);
    long end = System.nanoTime();

    assertTrue(end - start > delayForFinishEnqueueTask.toNanos());
    assertTrue(end - start < TIMEOUT.toNanos());
  }

  @Test
  void testWriteMonoRequestWithExceptionInPublication() {
    // the Publication is ready
    Mockito.when(messagePublication.onDispose()).thenReturn(Mono.never());

    // mono error request
    RuntimeException expected = new RuntimeException("request error");
    Mono<ByteBuffer> request = Mono.error(expected);

    StepVerifier.create(aeronWriteSequencer.write(request))
        .expectErrorSatisfies(
            actual -> {
              assertEquals(expected.getClass(), actual.getClass());
              assertEquals(expected.getMessage(), actual.getMessage());
            })
        .verify(TIMEOUT);
  }

  @Test
  void testWriteFluxRequestInPublicationAndWhileWaitForAllSendingWillCancelSubscription() {
    // the Publication is ready
    Mockito.when(messagePublication.onDispose()).thenReturn(Mono.never());

    // long wait task
    int fastTasks = PREFETCH;
    int waitingTasks = PREFETCH;
    ReplayProcessor<Integer> latch = ReplayProcessor.create();
    // generates responses
    List<Mono<Void>> responses =
        Stream.concat(
                IntStream.range(0, fastTasks).mapToObj(i -> Mono.<Void>empty()),
                IntStream.range(0, waitingTasks)
                    .mapToObj(
                        i ->
                            Mono.<Void>never()
                                .doOnNext(item -> latch.onError(fail("it was emitted an item")))
                                .doOnError(ex -> latch.onError(fail("it was emitted ex: ", ex)))
                                .doOnTerminate(
                                    () ->
                                        latch.onError(fail("it was emitted the completion signal")))
                                .doOnCancel(() -> latch.onNext(1))))
            .collect(Collectors.toList());
    // Shuffle is not important here, because it's not real MessagePublication, which supports order
    // sending tasks and blocks sending other tasks unless it completes previous.
    // Collections.shuffle(responses);
    Mono<Void> first = responses.get(0);
    Mono<Void>[] next = responses.stream().skip(1).toArray(Mono[]::new);

    Mockito.when(messagePublication.enqueue(Mockito.any())).thenReturn(first, next);

    // flux request
    Flux<ByteBuffer> request =
        Flux.range(0, FLUX_REQUESTS).map(i -> AeronUtils.stringToByteBuffer("test" + i));

    StepVerifier.create(aeronWriteSequencer.write(request))
        .thenAwait(TIMEOUT)
        // cancel the subscription as soon as pause is finished
        .thenCancel()
        .verify(TIMEOUT);

    assertEquals(Math.min(waitingTasks, PREFETCH), latch.take(TIMEOUT).count().block().intValue());
  }

  @Test
  void testWriteFluxRequestInPublicationAndWaitForSuccessfulAllSending() {
    // the Publication is ready
    Mockito.when(messagePublication.onDispose()).thenReturn(Mono.never());
    // long wait task
    Duration delayForFinishEnqueueTasks = TIMEOUT.dividedBy(2).dividedBy(FLUX_REQUESTS);
    Mockito.when(messagePublication.enqueue(Mockito.any()))
        .thenReturn(Mono.delay(delayForFinishEnqueueTasks).then());

    // flux request
    Flux<ByteBuffer> request =
        Flux.range(0, FLUX_REQUESTS).map(i -> AeronUtils.stringToByteBuffer("test" + i));

    long start = System.nanoTime();
    StepVerifier.create(aeronWriteSequencer.write(request)).expectComplete().verify(TIMEOUT);
    long end = System.nanoTime();

    assertTrue(
        end - start > delayForFinishEnqueueTasks.multipliedBy(FLUX_REQUESTS / PREFETCH).toNanos());
    assertTrue(end - start < TIMEOUT.toNanos());
  }

  @Test
  void testWriteFluxDelayFirstRequestInReadyPublicationAndThenCancelSubscription() {
    // the Publication is ready
    Mockito.when(messagePublication.onDispose()).thenReturn(Mono.never());

    // flux request with delay
    MonoProcessor<Throwable> latch = MonoProcessor.create();
    Flux<ByteBuffer> request =
        Flux.range(0, FLUX_REQUESTS)
            .delayElements(TIMEOUT)
            .map(i -> AeronUtils.stringToByteBuffer("test" + i))
            .doOnNext(item -> latch.onError(fail("it was emitted an item")))
            .doOnError(ex -> latch.onError(fail("it was emitted ex: ", ex)))
            .doOnTerminate(() -> latch.onError(fail("it was emitted the completion signal")))
            .doOnCancel(latch::onComplete);

    StepVerifier.create(aeronWriteSequencer.write(request))
        .thenAwait(TIMEOUT.dividedBy(2))
        // cancel the subscription as soon as pause is finished
        .thenCancel()
        .verify(TIMEOUT);

    assertNull(latch.block(TIMEOUT));
  }

  @Test
  void testWriteFluxRequestWithExceptionInPublicationAndWhileWaitForAllPreviousSendingTasks() {
    // the Publication is ready
    Mockito.when(messagePublication.onDispose()).thenReturn(Mono.never());

    // long wait tasks
    int waitingTasks = 10;
    waitingTasks = Math.min(waitingTasks, PREFETCH - 1);
    RuntimeException expected = new RuntimeException("request error");
    ReplayProcessor<Integer> latch = ReplayProcessor.create();

    // generates responses
    Mockito.when(messagePublication.enqueue(Mockito.any()))
        .thenReturn(
            Mono.<Void>never()
                .doOnNext(item -> latch.onError(fail("it was emitted an item")))
                .doOnError(ex -> latch.onError(fail("it was emitted ex: ", ex)))
                .doOnTerminate(() -> latch.onError(fail("it was emitted the completion signal")))
                .doOnCancel(() -> latch.onNext(1)));

    // flux request
    Flux<ByteBuffer> request =
        Flux.range(0, waitingTasks)
            .map(i -> AeronUtils.stringToByteBuffer("test" + i))
            .concatWith(Mono.error(expected));

    StepVerifier.create(aeronWriteSequencer.write(request))
        .expectErrorSatisfies(
            actual -> {
              assertEquals(expected.getClass(), actual.getClass());
              assertEquals(expected.getMessage(), actual.getMessage());
            })
        .verify(TIMEOUT);

    assertEquals(waitingTasks, latch.take(TIMEOUT).count().block().intValue());
  }

  @Test
  void testWriteFluxRequestInPublicationWhichIsAlreadyDisposed() {
    // the Publication is already disposed
    Mockito.when(messagePublication.onDispose()).thenReturn(Mono.empty());
    Mockito.when(messagePublication.isDisposed()).thenReturn(false);
    // long wait task
    Mockito.when(messagePublication.enqueue(Mockito.any())).thenReturn(Mono.never());
    RuntimeException expected = AeronExceptions.failWithMessagePublicationUnavailable();

    // flux request
    Flux<ByteBuffer> request =
        Flux.range(0, FLUX_REQUESTS)
            .subscribeOn(Schedulers.single())
            .publishOn(Schedulers.single())
            .map(i -> AeronUtils.stringToByteBuffer("test" + i));

    StepVerifier.create(aeronWriteSequencer.write(request).subscribeOn(Schedulers.single()))
        .expectErrorSatisfies(
            actual -> {
              assertEquals(expected.getClass(), actual.getClass());
              assertEquals(expected.getMessage(), actual.getMessage());
            })
        .verify(TIMEOUT);
  }

  @Test
  void testWriteFluxEmptyInPublicationWhichIsAlreadyDisposed() {
    // the Publication is already disposed
    Mockito.when(messagePublication.onDispose()).thenReturn(Mono.empty());

    RuntimeException expected = AeronExceptions.failWithMessagePublicationUnavailable();

    // flux empty request
    Flux<ByteBuffer> request = Flux.empty();

    StepVerifier.create(aeronWriteSequencer.write(request))
        .expectErrorSatisfies(
            actual -> {
              assertEquals(expected.getClass(), actual.getClass());
              assertEquals(expected.getMessage(), actual.getMessage());
            })
        .verify(TIMEOUT);
  }

  @Test
  void testWriteFluxRequestInPublicationWhichRejectsIncomingRequestsBySomeReasons() {
    // the Publication is ready
    Mockito.when(messagePublication.onDispose()).thenReturn(Mono.never());
    // publication rejects incoming requests
    RuntimeException expected = new RuntimeException("some reasons");

    Mono<Void> first = Mono.empty();
    Mono<Void>[] next = new Mono[] {Mono.<Void>error(expected)};
    Mockito.when(messagePublication.enqueue(Mockito.any())).thenReturn(first, next);

    // flux request
    Flux<ByteBuffer> request =
        Flux.range(0, FLUX_REQUESTS)
            .subscribeOn(Schedulers.parallel())
            .publishOn(Schedulers.parallel())
            .map(i -> AeronUtils.stringToByteBuffer("test" + i));


    StepVerifier.create(aeronWriteSequencer.write(request).subscribeOn(Schedulers.parallel()))
        .expectErrorSatisfies(
            actual -> {
              // workaround to get cause
              assertEquals("Failed to publish signal", actual.getMessage());
              assertNotNull(actual.getCause());

              assertEquals(expected.getClass(), actual.getCause().getClass());
              assertEquals(expected.getMessage(), actual.getCause().getMessage());
            })
        .verify(TIMEOUT);
  }

  @Test
  void testWriteFluxEmptyInReadyPublication() {
    // the Publication is ready
    Mockito.when(messagePublication.onDispose()).thenReturn(Mono.never());

    Flux<ByteBuffer> request = Flux.empty();

    StepVerifier.create(aeronWriteSequencer.write(request)).expectComplete().verify(TIMEOUT);
  }
}
