package com.miniwsa.api;

import com.miniwsa.alerts.AlertDefinition;
import com.miniwsa.alerts.AlertService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * {@code POST /v1/alerts/define}: stores an alert rule "more than {@code events} events of
 * {@code category} within {@code windowMinutes} minutes", e.g.
 * {@code {"name": "SQLi burst", "category": "INJECTION", "events": 100, "windowMinutes": 5}}.
 *
 * <p>Jackson reads the body into an {@link AlertDefinition} and Spring validates it before this method
 * runs; {@link ApiExceptionHandler} turns either kind of failure into a 400 with
 * {@code errors: [{field, message}]}. See {@link AlertDefinition} for the rules on each field.
 *
 * <p>On success the response is {@code 201 Created} with
 * {@code {"status": 201, "message": "Alert defined", "alert": {"id": 1, "name": ..., ...}}}.
 * Errors are problem details, which carry {@code "status": 400} the same way.
 * Each call adds a new rule; identical definitions are not merged.
 *
 * <p>{@code GET /v1/alerts/evaluate}: the rules firing now, as a JSON array of definitions ordered by
 * id ({@code []} when none fires). See {@link AlertService#evaluate}.
 */
@RestController
@RequestMapping("/v1/alerts")
public class AlertingController {

    private final AlertService alertService;
    private final JsonMapper jsonMapper;

    public AlertingController(AlertService alertService, JsonMapper jsonMapper) {
        this.alertService = alertService;
        this.jsonMapper = jsonMapper;
    }

    @PostMapping(path = "/define", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ObjectNode define(@Valid @RequestBody AlertDefinition definition) {
        AlertDefinition alert = alertService.define(definition);
        return jsonMapper.createObjectNode()
                .put("status", HttpStatus.CREATED.value())
                .put("message", "Alert defined")
                .set("alert", jsonMapper.valueToTree(alert));
    }

    @GetMapping("/evaluate")
    public List<AlertDefinition> evaluate() {
        return alertService.evaluate();
    }
}
