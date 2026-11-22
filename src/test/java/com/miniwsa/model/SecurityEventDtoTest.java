package com.miniwsa.model;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import jakarta.validation.ValidatorFactory;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityEventDtoTest {

    private static final String SAMPLE = """
            {
              "eventId": "evt-00132",
              "timestamp": "2026-05-20T14:32:10Z",
              "configId": 14227,
              "policyId": "pol_web1",
              "clientIp": "203.0.113.42",
              "hostname": "www.example.com",
              "path": "/api/v1/login",
              "method": "POST",
              "statusCode": 403,
              "userAgent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
              "rule": {
                "id": "950001",
                "name": "SQL_INJECTION",
                "message": "SQL Injection Attack Detected",
                "severity": "CRITICAL",
                "category": "INJECTION"
              },
              "action": "DENY",
              "geoLocation": { "country": "CN", "city": "Beijing" },
              "requestSize": 1024,
              "responseSize": 256
            }
            """;

    private static final JsonMapper mapper = JsonMapper.builder().build();
    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        validatorFactory.close();
    }

    @Test
    void assignmentSampleParsesAndIsValid() {
        SecurityEventDto event = mapper.readValue(SAMPLE, SecurityEventDto.class);

        assertThat(event.timestamp()).isEqualTo(Instant.parse("2026-05-20T14:32:10Z"));
        assertThat(event.rule().severity()).isEqualTo(Severity.CRITICAL);
        assertThat(event.rule().category()).isEqualTo(Category.INJECTION);
        assertThat(event.action()).isEqualTo(Action.DENY);
        assertThat(event.geoLocation().country()).isEqualTo("CN");
        assertThat(validator.validate(event)).isEmpty();
    }

    @Test
    void clientSuppliedReceivedAtIsIgnored() {
        String json = SAMPLE.replace("\"responseSize\": 256", "\"responseSize\": 256, \"receivedAt\": \"2020-01-01T00:00:00Z\"");

        SecurityEventDto event = mapper.readValue(json, SecurityEventDto.class);

        assertThat(event.receivedAt()).isNull();
    }

    @Test
    void withReceivedAtStampsACopy() {
        SecurityEventDto event = mapper.readValue(SAMPLE, SecurityEventDto.class);
        Instant now = Instant.parse("2026-05-20T14:32:11Z");

        SecurityEventDto stamped = event.withReceivedAt(now);

        assertThat(stamped.receivedAt()).isEqualTo(now);
        assertThat(stamped.eventId()).isEqualTo(event.eventId());
        assertThat(event.receivedAt()).isNull();
    }

    @Test
    void unknownEnumValueFailsParsing() {
        String json = SAMPLE.replace("\"DENY\"", "\"BLOCK\"");

        assertThatThrownBy(() -> mapper.readValue(json, SecurityEventDto.class))
                .hasMessageContaining("BLOCK");
    }

    @Test
    void malformedTimestampFailsParsing() {
        String json = SAMPLE.replace("2026-05-20T14:32:10Z", "20/05/2026 14:32");

        assertThatThrownBy(() -> mapper.readValue(json, SecurityEventDto.class))
                .hasMessageContaining("20/05/2026");
    }

    @Test
    void missingRequiredFieldsAreReported() {
        String json = SAMPLE
                .replace("\"eventId\": \"evt-00132\",", "")
                .replace("\"category\": \"INJECTION\"", "\"category\": null")
                .replace("\"country\": \"CN\"", "\"country\": \"China\"");

        SecurityEventDto event = mapper.readValue(json, SecurityEventDto.class);
        Set<ConstraintViolation<SecurityEventDto>> violations = validator.validate(event);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .containsExactlyInAnyOrder("eventId", "rule.category", "geoLocation.country");
    }
}
