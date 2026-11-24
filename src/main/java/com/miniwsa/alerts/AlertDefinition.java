package com.miniwsa.alerts;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.miniwsa.model.Category;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * An alert rule: it fires when more than {@code events} events of {@code category} happen within
 * {@code windowMinutes} minutes. Both the body of {@code POST /v1/alerts/define} and its response.
 *
 * <p>{@code id} is assigned by the database: it is ignored on input ({@code READ_ONLY}) and is null
 * until the rule is stored. The other fields are required and checked by bean validation.
 *
 * @param id            assigned by the database
 * @param name          free text chosen by the client, at most {@link #MAX_NAME_LENGTH} characters
 * @param category      the event category counted (exact enum name)
 * @param events        the threshold N; the rule fires when the count is strictly greater
 * @param windowMinutes the window Y in minutes, {@link #MIN_WINDOW_MINUTES} to {@link #MAX_WINDOW_MINUTES}
 */
public record AlertDefinition(
        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        Long id,

        @NotBlank(message = "is required")
        @Size(max = AlertDefinition.MAX_NAME_LENGTH, message = "must be at most {max} characters")
        String name,

        @NotNull(message = "is required")
        Category category,

        @NotNull(message = "is required")
        @Min(value = 0, message = "must be 0 or more")
        Integer events,

        @NotNull(message = "is required")
        @Min(value = AlertDefinition.MIN_WINDOW_MINUTES, message = "must be between {value} and 1440")
        @Max(value = AlertDefinition.MAX_WINDOW_MINUTES, message = "must be between 1 and {value}")
        Integer windowMinutes) {

    public static final int MAX_NAME_LENGTH = 128;
    public static final int MIN_WINDOW_MINUTES = 1;
    /** One day: bounds how many events a single rule has to count. */
    public static final int MAX_WINDOW_MINUTES = 1440;

    /** This definition with the id the database assigned. */
    public AlertDefinition withId(long id) {
        return new AlertDefinition(id, name, category, events, windowMinutes);
    }
}
