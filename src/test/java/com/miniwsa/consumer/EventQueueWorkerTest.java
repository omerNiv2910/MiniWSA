package com.miniwsa.consumer;

import com.miniwsa.config.MiniWsaProperties;
import com.miniwsa.model.Action;
import com.miniwsa.model.Category;
import com.miniwsa.model.SecurityEvent;
import com.miniwsa.model.Severity;
import com.miniwsa.queue.EventQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class EventQueueWorkerTest {

    private final MiniWsaProperties props = new MiniWsaProperties(new MiniWsaProperties.Queue(100, 10,
            Duration.ofMillis(20), Duration.ofMillis(10), Duration.ofMillis(40), Duration.ofSeconds(5)), null, null,
            new MiniWsaProperties.RateLimit(true, 100, Duration.ofMinutes(1)));
    private final EventQueue queue = new EventQueue(props);
    private final EventBatchHandler handler = mock(EventBatchHandler.class);
    private final EventQueueWorker worker = new EventQueueWorker(queue, handler, props);

    @AfterEach
    void stopWorker() {
        worker.stop();
    }

    @Test
    void queuedEventsReachTheHandler() {
        // Queue first: with the worker already running it could take evt-1 before evt-2 is added.
        queue.publish(List.of(event("evt-1"), event("evt-2")));
        worker.start();

        assertThat(handled(1)).extracting(SecurityEvent::eventId).containsExactly("evt-1", "evt-2");
    }

    @Test
    void failedBatchIsRetriedUntilItSucceeds() {
        doThrow(new IllegalStateException("database down"))
                .doThrow(new IllegalStateException("database down"))
                .doNothing()
                .when(handler).handle(anyList());
        worker.start();
        queue.publish(List.of(event("evt-1")));

        verify(handler, timeout(5_000).times(3)).handle(anyList());
        assertThat(handled(3)).extracting(SecurityEvent::eventId).containsExactly("evt-1");
    }

    @Test
    void stopHandlesWhatIsStillQueued() throws InterruptedException {
        CountDownLatch firstBatchStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> {
            firstBatchStarted.countDown();
            release.await();
            return null;
        }).doNothing().when(handler).handle(anyList());
        worker.start();
        queue.publish(List.of(event("evt-1")));
        assertThat(firstBatchStarted.await(5, TimeUnit.SECONDS)).isTrue();
        queue.publish(List.of(event("evt-2")));

        release.countDown();
        worker.stop();

        assertThat(worker.isRunning()).isFalse();
        assertThat(queue.isEmpty()).isTrue();
        verify(handler, times(2)).handle(anyList());
    }

    @SuppressWarnings("unchecked")
    private List<SecurityEvent> handled(int calls) {
        ArgumentCaptor<List<SecurityEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(handler, timeout(5_000).times(calls)).handle(captor.capture());
        return captor.getValue();
    }

    private static SecurityEvent event(String id) {
        return new SecurityEvent(id, Instant.parse("2026-05-20T14:32:10Z"), 14227L, null,
                "203.0.113.42", "www.example.com", "/", "GET", 403, null,
                new SecurityEvent.Rule("1", "R", null, Severity.LOW, Category.BOT),
                Action.DENY, null, null, null, null, null, null);
    }
}
