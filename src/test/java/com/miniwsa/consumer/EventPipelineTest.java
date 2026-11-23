package com.miniwsa.consumer;

import com.miniwsa.PostgresTestConfiguration;
import com.miniwsa.ingest.IngestService;
import com.miniwsa.model.Action;
import com.miniwsa.model.Category;
import com.miniwsa.model.SecurityEvent;
import com.miniwsa.model.Severity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/** Ingest → in-memory queue → worker → handler, with the real Spring wiring. */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class EventPipelineTest {

    @Autowired
    private IngestService ingestService;

    @MockitoBean
    private EventBatchHandler handler;

    @Test
    void ingestedEventReachesTheHandlerUnstamped() {
        ingestService.ingest(List.of(new SecurityEvent("evt-e2e-1", Instant.parse("2026-05-20T14:32:10Z"),
                14227L, "pol_web1", "203.0.113.42", "www.example.com", "/api/v1/login", "POST", 403, null,
                new SecurityEvent.Rule("950001", "SQL_INJECTION", null, Severity.CRITICAL, Category.INJECTION),
                Action.DENY, null, null, null, null, null, null)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SecurityEvent>> batches = ArgumentCaptor.forClass(List.class);
        verify(handler, timeout(10_000)).handle(batches.capture());
        SecurityEvent consumed = batches.getValue().getFirst();
        assertThat(consumed.eventId()).isEqualTo("evt-e2e-1");
        // The real handler stamps receivedAt; here it is mocked, so the event arrives as ingest left it.
        assertThat(consumed.receivedAt()).isNull();
    }
}
