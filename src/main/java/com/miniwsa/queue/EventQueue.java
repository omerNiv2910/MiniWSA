package com.miniwsa.queue;

import com.miniwsa.config.MiniWsaProperties;
import com.miniwsa.ingest.EventPublishException;
import com.miniwsa.model.SecurityEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Bounded in-memory queue between ingest and storage.
 *
 * <p>Ingest adds a whole request at once or nothing: when there isn't room for every event,
 * {@link #publish} throws and nothing is queued, so the client can simply retry the request.
 *
 * <p>Events live only in this process's memory. Anything still queued when the process dies is
 * lost; on a normal shutdown the worker drains the queue first (see {@code EventQueueWorker}).
 */
@Component
public class EventQueue {

    private final BlockingQueue<SecurityEvent> queue;
    private final int capacity;
    // Only publishers take this lock. The worker only removes events, which can only add room,
    // so the room checked under the lock is still there when the events are added.
    private final ReentrantLock publishLock = new ReentrantLock();

    @Autowired
    public EventQueue(MiniWsaProperties props) {
        this(props.queue().capacity());
    }

    EventQueue(int capacity) {
        this.capacity = capacity;
        this.queue = new LinkedBlockingQueue<>(capacity);
    }

    /**
     * Adds every event, or none of them.
     *
     * @throws EventPublishException if the queue doesn't have room for the whole batch
     */
    public void publish(List<SecurityEvent> events) {
        publishLock.lock();
        try {
            int room = queue.remainingCapacity();
            if (room < events.size()) {
                throw new EventPublishException("Event queue is full: room for " + room + " of "
                        + events.size() + " events (capacity " + capacity + ")");
            }
            queue.addAll(events);
        } finally {
            publishLock.unlock();
        }
    }

    /**
     * Waits up to {@code wait} for the first event, then takes whatever else is already queued,
     * up to {@code maxEvents} in total.
     * @return the events taken, in queue order; empty if none arrived in time
     */
    public List<SecurityEvent> take(int maxEvents, Duration wait) throws InterruptedException {
        SecurityEvent first = queue.poll(wait.toNanos(), TimeUnit.NANOSECONDS);
        if (first == null) {
            return List.of();
        }
        List<SecurityEvent> batch = new ArrayList<>(Math.min(maxEvents, queue.size() + 1));
        batch.add(first);
        queue.drainTo(batch, maxEvents - 1);
        return batch;
    }

    public int size() {
        return queue.size();
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }
}
