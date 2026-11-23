package com.miniwsa.consumer;

import com.miniwsa.enrichment.ClientIpTracker;
import com.miniwsa.model.Action;
import com.miniwsa.model.Category;
import com.miniwsa.model.SecurityEvent;
import com.miniwsa.model.Severity;
import com.miniwsa.storage.EventStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StoringEventBatchHandlerTest {

    private static final Instant NOW = Instant.parse("2026-05-20T14:32:11Z");

    private final ClientIpTracker tracker = mock(ClientIpTracker.class);
    private final EventStore store = mock(EventStore.class);
    private final StoringEventBatchHandler handler =
            new StoringEventBatchHandler(tracker, store, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void noPriorEventsByDefault() {
        when(tracker.priorCounts(anyList()))
                .thenAnswer(inv -> Collections.nCopies(inv.<List<?>>getArgument(0).size(), 0L));
    }

    @Test
    @SuppressWarnings("unchecked")
    void stampsAndEnrichesEveryEventBeforeSavingTheBatch() {
        handler.handle(List.of(
                event("evt-1", "/api/v1/login", Severity.CRITICAL, Category.INJECTION, Action.DENY),
                event("evt-2", "/", Severity.LOW, Category.BOT, Action.MONITOR)));

        ArgumentCaptor<List<SecurityEvent>> saved = ArgumentCaptor.forClass(List.class);
        verify(store).saveBatch(saved.capture());
        assertThat(saved.getValue()).extracting(SecurityEvent::eventId).containsExactly("evt-1", "evt-2");
        assertThat(saved.getValue()).extracting(SecurityEvent::receivedAt).containsOnly(NOW);
        assertThat(saved.getValue()).extracting(SecurityEvent::attackType)
                .containsExactly("SQL/Command Injection", "Bot Activity");
        assertThat(saved.getValue()).extracting(SecurityEvent::threatScore).containsExactly(75, 10);
    }

    @Test
    void appliesEachPriorCountToTheEventAtTheSamePosition() {
        List<SecurityEvent> batch = List.of(
                event("evt-1", "/api/v1/login", Severity.CRITICAL, Category.INJECTION, Action.DENY),
                event("evt-2", "/api/v1/login", Severity.CRITICAL, Category.INJECTION, Action.DENY));
        when(tracker.priorCounts(batch)).thenReturn(List.of(4L, 5L));

        handler.handle(batch);

        assertThat(batch).extracting(SecurityEvent::threatScore).containsExactly(75, 90);
    }

    @Test
    void asksTheTrackerOnceForTheWholeBatchBeforeSaving() {
        List<SecurityEvent> batch = List.of(event("evt-1", "/", Severity.LOW, Category.BOT, Action.DENY));

        handler.handle(batch);

        InOrder order = inOrder(tracker, store);
        order.verify(tracker).priorCounts(batch);
        order.verify(store).saveBatch(batch);
    }

    @Test
    void trackerFailureSavesNothingAndReachesTheWorker() {
        when(tracker.priorCounts(anyList())).thenThrow(new IllegalStateException("redis down"));

        assertThatThrownBy(() -> handler.handle(List.of(
                event("evt-1", "/", Severity.LOW, Category.BOT, Action.DENY))))
                .isInstanceOf(IllegalStateException.class);
        verify(store, never()).saveBatch(any());
    }

    @Test
    void wrongNumberOfCountsIsRejected() {
        when(tracker.priorCounts(anyList())).thenReturn(List.of());

        assertThatThrownBy(() -> handler.handle(List.of(
                event("evt-1", "/", Severity.LOW, Category.BOT, Action.DENY))))
                .isInstanceOf(IllegalStateException.class);
        verify(store, never()).saveBatch(any());
    }

    @Test
    void retriedBatchIsStampedWithTheRetryTime() {
        SecurityEvent event = event("evt-1", "/", Severity.LOW, Category.BOT, Action.DENY);
        doThrow(new IllegalStateException("db down")).when(store).saveBatch(anyList());
        assertThatThrownBy(() -> handler.handle(List.of(event))).isInstanceOf(IllegalStateException.class);

        var later = new StoringEventBatchHandler(tracker, mock(EventStore.class),
                Clock.fixed(NOW.plusSeconds(30), ZoneOffset.UTC));
        later.handle(List.of(event));

        assertThat(event.receivedAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(event.threatScore()).isEqualTo(30);
    }

    @Test
    void storeFailureReachesTheWorkerSoTheBatchIsRetried() {
        doThrow(new IllegalStateException("db down")).when(store).saveBatch(anyList());

        assertThatThrownBy(() -> handler.handle(List.of(
                event("evt-1", "/", Severity.LOW, Category.BOT, Action.DENY))))
                .isInstanceOf(IllegalStateException.class);
    }

    private static SecurityEvent event(String id, String path, Severity severity, Category category, Action action) {
        return new SecurityEvent(id, Instant.parse("2026-05-20T14:32:10Z"), 14227L, null,
                "203.0.113.42", "www.example.com", path, "GET", 403, null,
                new SecurityEvent.Rule("1", "R", null, severity, category),
                action, null, null, null, null, null, null);
    }
}
