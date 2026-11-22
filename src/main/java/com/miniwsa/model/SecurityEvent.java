package com.miniwsa.model;

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
 * ({@code READ_ONLY}) and the ingest layer stamps it with {@link #withReceivedAt(Instant)}.
 */
public record SecurityEvent(
        @NotBlank @Size(max = 128) String eventId,
        @NotNull Instant timestamp,
        @NotNull @Positive Long configId,
        @Size(max = 128) String policyId,
        @NotBlank @Size(max = 45) String clientIp,
        @NotBlank @Size(max = 255) String hostname,
        @NotBlank @Size(max = 2048) String path,
        @NotBlank @Size(max = 16) String method,
        @NotNull @Min(100) @Max(599) Integer statusCode,
        @Size(max = 1024) String userAgent,
        @NotNull @Valid Rule rule,
        @NotNull Action action,
        @Valid GeoLocation geoLocation,
        @PositiveOrZero Long requestSize,
        @PositiveOrZero Long responseSize,
        @JsonProperty(access = JsonProperty.Access.READ_ONLY) Instant receivedAt
) {

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

    /** Returns a copy of this event stamped with the server-side receive time. */
    public SecurityEvent withReceivedAt(Instant receivedAt) {
        return new SecurityEvent(eventId, timestamp, configId, policyId, clientIp, hostname, path,
                method, statusCode, userAgent, rule, action, geoLocation, requestSize, responseSize,
                receivedAt);
    }
}
