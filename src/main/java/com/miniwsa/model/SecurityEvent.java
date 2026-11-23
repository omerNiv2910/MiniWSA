package com.miniwsa.model;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * A security event (DLR) as received from the edge.
 *
 * <p>Required fields are the ones the pipeline depends on: identity ({@code eventId}), time,
 * tenant ({@code configId}), the request that was hit, and the rule and action used for
 * classification and scoring. Descriptive fields ({@code policyId}, {@code userAgent},
 * {@code geoLocation}, sizes, {@code rule.message}) are optional.
 *
 * <p>Enum values must match exactly (e.g. {@code "DENY"}), and {@code timestamp} must be an
 * ISO-8601 instant, so a bad value fails JSON parsing before validation runs.
 *
 * <p>{@code receivedAt} is set by the server, never by the client: Jackson ignores it on input
 * ({@code READ_ONLY}) and the ingest layer sets it with {@link #setReceivedAt(Instant)} before publishing.
 *
 * <p>{@code attackType} and {@code threatScore} are server-set as well, but later: the queue consumer
 * sets them with {@link #setAttackType(String)} and {@link #setThreatScore(Integer)} just before storing,
 * never before publishing. They may be null (not enriched yet), are ignored on input, and are left out of
 * the JSON while null.
 *
 * <p>These three server-set fields are the only mutable ones. Because the consumer changes events in
 * place, once an event is published nothing else may read or change it on another thread.
 *
 * <p>Jackson reads and writes the fields directly; the record-style accessors ({@code eventId()}) are for
 * Java code only.
 */
@JsonAutoDetect(fieldVisibility = Visibility.ANY, getterVisibility = Visibility.NONE,
        isGetterVisibility = Visibility.NONE, setterVisibility = Visibility.NONE)
public final class SecurityEvent {

    @NotBlank @Size(max = 128) private final String eventId;
    @NotNull private final Instant timestamp;
    @NotNull @Positive private final Long configId;
    @Size(max = 128) private final String policyId;
    @NotBlank @Size(max = 45) private final String clientIp;
    @NotBlank @Size(max = 255) private final String hostname;
    @NotBlank @Size(max = 2048) private final String path;
    @NotBlank @Size(max = 16) private final String method;
    @NotNull @Min(100) @Max(599) private final Integer statusCode;
    @Size(max = 1024) private final String userAgent;
    @NotNull @Valid private final Rule rule;
    @NotNull private final Action action;
    @Valid private final GeoLocation geoLocation;
    @PositiveOrZero private final Long requestSize;
    @PositiveOrZero private final Long responseSize;

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private Instant receivedAt;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String attackType;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer threatScore;

    public SecurityEvent(String eventId, Instant timestamp, Long configId, String policyId, String clientIp,
                         String hostname, String path, String method, Integer statusCode, String userAgent,
                         Rule rule, Action action, GeoLocation geoLocation, Long requestSize, Long responseSize,
                         Instant receivedAt, String attackType, Integer threatScore) {
        this.eventId = eventId;
        this.timestamp = timestamp;
        this.configId = configId;
        this.policyId = policyId;
        this.clientIp = clientIp;
        this.hostname = hostname;
        this.path = path;
        this.method = method;
        this.statusCode = statusCode;
        this.userAgent = userAgent;
        this.rule = rule;
        this.action = action;
        this.geoLocation = geoLocation;
        this.requestSize = requestSize;
        this.responseSize = responseSize;
        this.receivedAt = receivedAt;
        this.attackType = attackType;
        this.threatScore = threatScore;
    }

    /** Used by Jackson: only client-supplied fields, so the server-set ones always start out null. */
    @JsonCreator
    private SecurityEvent(@JsonProperty("eventId") String eventId,
                          @JsonProperty("timestamp") Instant timestamp,
                          @JsonProperty("configId") Long configId,
                          @JsonProperty("policyId") String policyId,
                          @JsonProperty("clientIp") String clientIp,
                          @JsonProperty("hostname") String hostname,
                          @JsonProperty("path") String path,
                          @JsonProperty("method") String method,
                          @JsonProperty("statusCode") Integer statusCode,
                          @JsonProperty("userAgent") String userAgent,
                          @JsonProperty("rule") Rule rule,
                          @JsonProperty("action") Action action,
                          @JsonProperty("geoLocation") GeoLocation geoLocation,
                          @JsonProperty("requestSize") Long requestSize,
                          @JsonProperty("responseSize") Long responseSize) {
        this(eventId, timestamp, configId, policyId, clientIp, hostname, path, method, statusCode, userAgent,
                rule, action, geoLocation, requestSize, responseSize, null, null, null);
    }

    /** The WAF rule that fired for this request. */
    public record Rule(
            @NotBlank @Size(max = 64) String id,
            @NotBlank @Size(max = 128) String name,
            @Size(max = 512) String message,
            @NotNull Severity severity,
            @NotNull Category category
    ) {
    }

    /** Where the client IP geolocates to. {@code country} is an ISO 3166-1 alpha-2 code. */
    public record GeoLocation(
            @Pattern(regexp = "^[A-Z]{2}$", message = "must be a 2-letter ISO country code") String country,
            @Size(max = 128) String city
    ) {
    }

    /** Stamps this event with the server-side receive time. */
    public void setReceivedAt(Instant receivedAt) {
        this.receivedAt = receivedAt;
    }

    public void setAttackType(String attackType) {
        this.attackType = attackType;
    }

    public void setThreatScore(Integer threatScore) {
        this.threatScore = threatScore;
    }

    public String eventId() {
        return eventId;
    }

    public Instant timestamp() {
        return timestamp;
    }

    public Long configId() {
        return configId;
    }

    public String policyId() {
        return policyId;
    }

    public String clientIp() {
        return clientIp;
    }

    public String hostname() {
        return hostname;
    }

    public String path() {
        return path;
    }

    public String method() {
        return method;
    }

    public Integer statusCode() {
        return statusCode;
    }

    public String userAgent() {
        return userAgent;
    }

    public Rule rule() {
        return rule;
    }

    public Action action() {
        return action;
    }

    public GeoLocation geoLocation() {
        return geoLocation;
    }

    public Long requestSize() {
        return requestSize;
    }

    public Long responseSize() {
        return responseSize;
    }

    public Instant receivedAt() {
        return receivedAt;
    }

    public String attackType() {
        return attackType;
    }

    public Integer threatScore() {
        return threatScore;
    }

    @Override
    public String toString() {
        return "SecurityEvent[eventId=" + eventId + ", timestamp=" + timestamp + ", clientIp=" + clientIp
                + ", path=" + path + ", rule=" + rule + ", action=" + action + ", receivedAt=" + receivedAt
                + ", attackType=" + attackType + ", threatScore=" + threatScore + "]";
    }
}
