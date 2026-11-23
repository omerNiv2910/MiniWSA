package com.miniwsa.queue;

import com.miniwsa.ingest.EventPublishException;
import com.miniwsa.model.Action;
import com.miniwsa.model.Category;
import com.miniwsa.model.SecurityEvent;
import com.miniwsa.model.Severity;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventQueueTest {

    private final EventQueue queue = new EventQueue(3);

    @Test
    void takesEventsInOrderUpToTheBatchSize() throws InterruptedException {
        queue.publish(List.of(event("a"), event("b"), event("c")));

        assertThat(queue.take(2, Duration.ZERO)).extracting(SecurityEvent::eventId).containsExactly("a", "b");
        assertThat(queue.take(2, Duration.ZERO)).extracting(SecurityEvent::eventId).containsExactly("c");
    }

    @Test
    void batchThatDoesNotFitIsRejectedWhole() {
        queue.publish(List.of(event("a"), event("b")));

        assertThatThrownBy(() -> queue.publish(List.of(event("c"), event("d"))))
                .isInstanceOf(EventPublishException.class)
                .hasMessageContaining("room for 1 of 2");
        assertThat(queue.size()).isEqualTo(2);
    }

    @Test
    void takeReturnsEmptyWhenNothingArrives() throws InterruptedException {
        assertThat(queue.take(10, Duration.ofMillis(10))).isEmpty();
    }

    static SecurityEvent event(String id) {
        return new SecurityEvent(id, Instant.parse("2026-05-20T14:32:10Z"), 14227L, null,
                "203.0.113.42", "www.example.com", "/", "GET", 403, null,
                new SecurityEvent.Rule("1", "R", null, Severity.LOW, Category.BOT),
                Action.DENY, null, null, null, null);
    }
}
