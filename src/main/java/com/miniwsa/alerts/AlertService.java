package com.miniwsa.alerts;

import com.miniwsa.storage.AlertStore;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;

/**
 * Defines alert rules and evaluates them. {@link #evaluate} reads the clock once and asks
 * {@link AlertStore#findFiring} which rules fire at that instant; the counting and the comparison
 * happen in one SQL query.
 */
@Service
public class AlertService {

    private final AlertStore alertStore;
    private final Clock clock;

    public AlertService(AlertStore alertStore, Clock clock) {
        this.alertStore = alertStore;
        this.clock = clock;
    }

    /** Stores one (already validated) definition and returns it with its id. */
    public AlertDefinition define(AlertDefinition definition) {
        return alertStore.define(definition);
    }

    /**
     * The rules firing now, ordered by id: those with strictly more than {@code events} events of their
     * category in the last {@code windowMinutes} minutes. Empty when none fires or none is defined.
     */
    public List<AlertDefinition> evaluate() {
        return alertStore.findFiring(clock.instant());
    }
}
