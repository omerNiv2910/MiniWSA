package com.miniwsa.ingest;

import com.miniwsa.model.Action;
import com.miniwsa.model.Category;
import com.miniwsa.model.SecurityEvent;
import com.miniwsa.model.Severity;
import com.miniwsa.queue.EventQueue;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class IngestServiceTest {

    private final EventQueue queue = mock(EventQueue.class);
    private final IngestService service = new IngestService(queue);

    @Test
    void returnsHowManyEventsWereQueued() {
        assertThat(service.ingest(List.of(event("evt-1"), event("evt-2")))).isEqualTo(2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void queuesTheSameEventObjectsAsOneBatch() {
        List<SecurityEvent> events = List.of(event("evt-1"), event("evt-2"));

        service.ingest(events);

        ArgumentCaptor<List<SecurityEvent>> queued = ArgumentCaptor.forClass(List.class);
        verify(queue).publish(queued.capture());
        assertThat(queued.getValue()).isSameAs(events);
    }

    @Test
    @SuppressWarnings("unchecked")
    void setsNoServerFieldsBeforePublishing() {
        service.ingest(List.of(event("evt-1")));

        ArgumentCaptor<List<SecurityEvent>> queued = ArgumentCaptor.forClass(List.class);
        verify(queue).publish(queued.capture());
        assertThat(queued.getValue()).extracting(SecurityEvent::receivedAt).containsOnlyNulls();
        assertThat(queued.getValue()).extracting(SecurityEvent::attackType).containsOnlyNulls();
        assertThat(queued.getValue()).extracting(SecurityEvent::threatScore).containsOnlyNulls();
    }

    @Test
    void fullQueueIsReported() {
        doThrow(new EventPublishException("Event queue is full")).when(queue).publish(anyList());

        assertThatThrownBy(() -> service.ingest(List.of(event("evt-1"))))
                .isInstanceOf(EventPublishException.class);
    }

    private static SecurityEvent event(String id) {
        return new SecurityEvent(id, Instant.parse("2026-05-20T14:32:10Z"), 14227L, "pol_web1",
                "203.0.113.42", "www.example.com", "/api/v1/login", "POST", 403, null,
                new SecurityEvent.Rule("950001", "SQL_INJECTION", null, Severity.CRITICAL, Category.INJECTION),
                Action.DENY, null, null, null, null, null, null);
    }
}
