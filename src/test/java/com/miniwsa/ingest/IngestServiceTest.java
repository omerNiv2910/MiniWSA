package com.miniwsa.ingest;

import com.miniwsa.config.MiniWsaProperties;
import com.miniwsa.model.Action;
import com.miniwsa.model.Category;
import com.miniwsa.model.SecurityEvent;
import com.miniwsa.model.Severity;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IngestServiceTest {

    private static final Instant NOW = Instant.parse("2026-05-20T14:32:11Z");
    private static final String TOPIC = "dlr.events.raw";

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, SecurityEvent> kafka = mock(KafkaTemplate.class);

    private final IngestService service = new IngestService(kafka, Clock.fixed(NOW, ZoneOffset.UTC),
            new MiniWsaProperties(new MiniWsaProperties.Topics(TOPIC, "dlr.events.dlq"),
                    new MiniWsaProperties.Ingest(Duration.ofMillis(200))));

    @BeforeEach
    void brokerAcksEverything() {
        when(kafka.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));
    }

    @Test
    void stampsEveryEventWithTheReceiveTime() {
        List<SecurityEvent> result = service.ingest(List.of(event("evt-1", "203.0.113.42"),
                event("evt-2", "198.51.100.7")));

        assertThat(result).extracting(SecurityEvent::receivedAt).containsOnly(NOW);
        assertThat(result).extracting(SecurityEvent::eventId).containsExactly("evt-1", "evt-2");
    }

    @Test
    void sendsEachStampedEventToTheRawTopicKeyedByClientIp() {
        service.ingest(List.of(event("evt-1", "203.0.113.42"), event("evt-2", "198.51.100.7")));

        ArgumentCaptor<SecurityEvent> sent = ArgumentCaptor.forClass(SecurityEvent.class);
        verify(kafka).send(eq(TOPIC), eq("203.0.113.42"), sent.capture());
        verify(kafka).send(eq(TOPIC), eq("198.51.100.7"), sent.capture());
        assertThat(sent.getAllValues()).extracting(SecurityEvent::eventId).containsExactly("evt-1", "evt-2");
        assertThat(sent.getAllValues()).extracting(SecurityEvent::receivedAt).containsOnly(NOW);
    }

    @Test
    void failedAckIsReported() {
        when(kafka.send(anyString(), eq("198.51.100.7"), any()))
                .thenReturn(CompletableFuture.failedFuture(new TimeoutException("expired")));

        assertThatThrownBy(() -> service.ingest(List.of(event("evt-1", "203.0.113.42"),
                event("evt-2", "198.51.100.7"))))
                .isInstanceOf(EventPublishException.class)
                .hasCauseInstanceOf(TimeoutException.class);
    }

    @Test
    void missingAckWithinTheSendTimeoutIsReported() {
        when(kafka.send(anyString(), anyString(), any())).thenReturn(new CompletableFuture<>());

        assertThatThrownBy(() -> service.ingest(List.of(event("evt-1", "203.0.113.42"))))
                .isInstanceOf(EventPublishException.class)
                .hasMessageContaining("PT0.2S");
    }

    @Test
    void sendFailureStopsTheBatch() {
        when(kafka.send(anyString(), anyString(), any())).thenThrow(new KafkaException("no metadata"));

        assertThatThrownBy(() -> service.ingest(List.of(event("evt-1", "203.0.113.42"),
                event("evt-2", "198.51.100.7"))))
                .isInstanceOf(EventPublishException.class)
                .hasCauseInstanceOf(KafkaException.class);
        verify(kafka, times(1)).send(anyString(), anyString(), any());
    }

    @Test
    void recordValueIsPlainJsonIncludingReceivedAt() {
        try (JacksonJsonSerializer<SecurityEvent> serializer = new JacksonJsonSerializer<>()) {
            String json = new String(serializer.serialize(TOPIC,
                    event("evt-1", "203.0.113.42").withReceivedAt(NOW)), StandardCharsets.UTF_8);

            assertThat(json)
                    .contains("\"eventId\":\"evt-1\"")
                    .contains("\"timestamp\":\"2026-05-20T14:32:10Z\"")
                    .contains("\"receivedAt\":\"2026-05-20T14:32:11Z\"")
                    .contains("\"category\":\"INJECTION\"");
        }
    }

    private static SecurityEvent event(String id, String clientIp) {
        return new SecurityEvent(id, Instant.parse("2026-05-20T14:32:10Z"), 14227L, "pol_web1",
                clientIp, "www.example.com", "/api/v1/login", "POST", 403, null,
                new SecurityEvent.Rule("950001", "SQL_INJECTION", null, Severity.CRITICAL, Category.INJECTION),
                Action.DENY, null, null, null, null);
    }
}
