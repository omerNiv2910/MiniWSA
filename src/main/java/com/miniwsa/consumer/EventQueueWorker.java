package com.miniwsa.consumer;

import com.miniwsa.config.MiniWsaProperties;
import com.miniwsa.model.SecurityEvent;
import com.miniwsa.queue.EventQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Takes batches of events from the {@link EventQueue} on one background thread and passes them
 * to the {@link EventBatchHandler}.
 *
 * <p>Events were validated by the API before they were queued, so every one can be handled.
 * When the handler fails (PostgreSQL down), the same batch is retried with exponential backoff until
 * it succeeds; meanwhile the queue fills up and ingest starts answering 503. A batch can therefore
 * be handled more than once, so the handler must be idempotent.
 *
 * <p>On shutdown the worker stops after the web server (so no new events arrive), handles what is
 * left in the queue with one attempt per batch and logs anything it had to drop.
 */
@Component
public class EventQueueWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(EventQueueWorker.class);

    // Lower than the web server's phases: starts before it and stops after it.
    private static final int PHASE = SmartLifecycle.DEFAULT_PHASE - 4096;

    private final EventQueue queue;
    private final EventBatchHandler handler;
    private final MiniWsaProperties.Queue settings;

    private volatile Thread thread;
    private volatile CountDownLatch stopping = new CountDownLatch(0);

    public EventQueueWorker(EventQueue queue, EventBatchHandler handler, MiniWsaProperties props) {
        this.queue = queue;
        this.handler = handler;
        this.settings = props.queue();
    }

    @Override
    public synchronized void start() {
        if (isRunning()) {
            return;
        }
        stopping = new CountDownLatch(1);
        thread = Thread.ofPlatform().name("event-queue-worker").daemon().start(this::run);
    }

    @Override
    public synchronized void stop() {
        Thread worker = thread;
        if (worker == null) {
            return;
        }
        stopping.countDown();
        try {
            worker.join(settings.shutdownTimeout());
            if (worker.isAlive()) {
                log.warn("Event queue worker did not finish within {}; {} queued events are dropped",
                        settings.shutdownTimeout(), queue.size());
                worker.interrupt();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        thread = null;
    }

    @Override
    public boolean isRunning() {
        return thread != null;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    private void run() {
        // Keep going while no stop was requested (latch still at 1); after a stop, until the queue is empty.
        while (stopping.getCount() > 0 || !queue.isEmpty()) {
            List<SecurityEvent> batch;
            try {
                batch = queue.take(settings.batchSize(), settings.pollTimeout());
            } catch (InterruptedException e) {
                break;
            }
            if (!batch.isEmpty()) {
                handleWithRetry(batch);
            }
        }
    }

    private void handleWithRetry(List<SecurityEvent> batch) {
        Duration backoff = settings.retryInitialInterval();
        while (true) {
            try {
                handler.handle(batch);
                return;
            } catch (RuntimeException e) {
                // Latch at 0 means stop() was called: don't retry while shutting down.
                if (stopping.getCount() == 0) {
                    log.error("Dropping {} events: handling failed while shutting down", batch.size(), e);
                    return;
                }
                log.warn("Handling {} events failed; retrying in {}", batch.size(), backoff, e);
            }
            try {
                // Returns early (true) when a stop is requested, which ends the retries.
                if (stopping.await(backoff.toMillis(), TimeUnit.MILLISECONDS)) {
                    log.error("Dropping {} events: shutting down while waiting to retry", batch.size());
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            backoff = min(backoff.multipliedBy(2), settings.retryMaxInterval());
        }
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }
}
