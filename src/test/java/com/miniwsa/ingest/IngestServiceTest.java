package com.miniwsa.ingest;

import com.miniwsa.model.Action;
import com.miniwsa.model.Category;
import com.miniwsa.model.SecurityEvent;
import com.miniwsa.model.Severity;
import com.miniwsa.queue.EventQueue;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class IngestServiceTest {

    private static final Instant NOW = Instant.parse("2026-05-20T14:32:11Z");

    private final EventQueue queue = mock(EventQueue.class);
    private final IngestService service = new IngestService(queue, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void stampsEveryEventWithTheReceiveTime() {
        List<SecurityEvent> result = service.ingest(List.of(event("evt-1"), event("evt-2")));

        assertThat(result).extracting(SecurityEvent::receivedAt).containsOnly(NOW);
        assertThat(result).extracting(SecurityEvent::eventId).containsExactly("evt-1", "evt-2");
    }

    @Test
    @SuppressWarnings("unchecked")
    void queuesTheStampedEventsAsOneBatch() {
        service.ingest(List.of(event("evt-1"), event("evt-2")));

        ArgumentCaptor<List<SecurityEvent>> queued = ArgumentCaptor.forClass(List.class);
        verify(queue).publish(queued.capture());
        assertThat(queued.getValue()).extracting(SecurityEvent::eventId).containsExactly("evt-1", "evt-2");
        assertThat(queued.getValue()).extracting(SecurityEvent::receivedAt).containsOnly(NOW);
    }

    @Test
    void fullQueueIsReported() {
        doThrow(new EventPublishException("Event queue is full")).when(queue).publish(anyList());

        assertThatThrownBy(() -> service.ingest(List.of(event("evt-1"))))
                .isInstanceOf(EventPublishException.class);
    }

    @Test
    void jsonIncludesTheServerReceiveTime() {
        String json = JsonMapper.builder().build()
                .writeValueAsString(event("evt-1").withReceivedAt(NOW));

        assertThat(json).contains("\"eventId\":\"evt-1\"").contains("\"receivedAt\"");
    }

    private static SecurityEvent event(String id) {
        return new SecurityEvent(id, Instant.parse("2026-05-20T14:32:10Z"), 14227L, "pol_web1",
                "203.0.113.42", "www.example.com", "/api/v1/login", "POST", 403, null,
                new SecurityEvent.Rule("950001", "SQL_INJECTION", null, Severity.CRITICAL, Category.INJECTION),
                Action.DENY, null, null, null, null);
    }
}
