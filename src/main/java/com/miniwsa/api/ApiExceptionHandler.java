package com.miniwsa.api;

import com.miniwsa.alerts.AlertDefinition;
import com.miniwsa.ingest.EventPublishException;
import com.miniwsa.ratelimit.RateLimitExceededException;
import com.miniwsa.storage.ReadQueryTimeoutException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.core.exc.InputCoercionException;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.exc.MismatchedInputException;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Renders request errors as problem details with an {@code errors} list: {@link EventError}s for the
 * ingest body, so clients can see which event and which field is wrong, {@link QueryParamError}s
 * for query parameters, and {@link AlertFieldError}s for an alert definition body.
 *
 * <p>Extending {@link ResponseEntityExceptionHandler} replaces Spring Boot's default problem-details
 * handler, so every other standard MVC exception still gets a problem-details response.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    /** Sent with a 503 so clients back off briefly before retrying. */
    private static final String RETRY_AFTER_SECONDS = "5";

    private static final String INVALID_ALERT_DETAIL = "Invalid alert definition";

    /** Alert body errors are listed in the order the fields appear in {@link AlertDefinition}. */
    private static final List<String> ALERT_FIELD_ORDER = Arrays.stream(AlertDefinition.class.getRecordComponents())
            .map(RecordComponent::getName)
            .toList();

    /** Shown for a bad timestamp, in an event body or in a query parameter. */
    public static final String ISO_TIMESTAMP_MESSAGE = "must be an ISO-8601 timestamp, e.g. 2026-05-20T14:32:10Z";

    /**
     * Bean validation failures. On query parameters (e.g. {@code limit=101} on samples) they are reported
     * as {@link QueryParamError}s, all together. Otherwise they are on list elements of the ingest body,
     * e.g. a missing {@code clientIp} in event 3.
     */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (ex.getParameterValidationResults().stream().allMatch(ApiExceptionHandler::isQueryParameter)) {
            List<QueryParamError> errors = new ArrayList<>();
            for (ParameterValidationResult result : ex.getParameterValidationResults()) {
                String name = queryParameterName(result.getMethodParameter());
                for (MessageSourceResolvable error : result.getResolvableErrors()) {
                    errors.add(new QueryParamError(name, error.getDefaultMessage()));
                }
            }
            return badQuery(ex, errors, headers, request);
        }

        List<EventError> errors = new ArrayList<>();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            int index = result.getContainerIndex() != null ? result.getContainerIndex() : 0;
            if (result instanceof ParameterErrors parameterErrors) {
                for (FieldError fe : parameterErrors.getFieldErrors()) {
                    errors.add(new EventError(index, fe.getField(), fe.getDefaultMessage()));
                }
            } else {
                // A constraint on the element itself, e.g. @NotNull on a null array item.
                for (MessageSourceResolvable error : result.getResolvableErrors()) {
                    errors.add(new EventError(index, "", error.getDefaultMessage()));
                }
            }
        }
        errors.sort(Comparator.comparingInt(EventError::index).thenComparing(EventError::field));
        return badRequest(ex, "Request contains invalid events", errors, headers, request);
    }

    /**
     * {@code @Valid} on a single (non-list) request body. There is no batch, so the index is 0.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (ex.getBindingResult().getTarget() instanceof AlertDefinition) {
            List<AlertFieldError> errors = ex.getBindingResult().getFieldErrors().stream()
                    .map(fe -> new AlertFieldError(fe.getField(), fe.getDefaultMessage()))
                    .sorted(Comparator.comparingInt((AlertFieldError e) -> ALERT_FIELD_ORDER.indexOf(e.field()))
                            .thenComparing(AlertFieldError::message))
                    .toList();
            return badAlert(ex, errors, headers, request);
        }
        List<EventError> errors = new ArrayList<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            errors.add(new EventError(0, fe.getField(), fe.getDefaultMessage()));
        }
        for (ObjectError oe : ex.getBindingResult().getGlobalErrors()) {
            errors.add(new EventError(0, "", oe.getDefaultMessage()));
        }
        errors.sort(Comparator.comparing(EventError::field));
        return badRequest(ex, "Request contains invalid events", errors, headers, request);
    }

    /**
     * JSON that could not be turned into a list of events. Jackson stops at the first problem, so
     * at most one {@link EventError} is reported.
     */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (!(ex.getCause() instanceof JacksonException je)) {
            return badRequest(ex, "Request body is missing or unreadable", List.of(), headers, request);
        }
        // A number too big for its field is a StreamReadException too, but the JSON itself is fine: for
        // an alert body it is reported against the field below.
        boolean alertNumberOverflow = je instanceof InputCoercionException && isAlertsRequest(request);
        if (je instanceof StreamReadException && !alertNumberOverflow) {
            return badRequest(ex, "Request body is not valid JSON", List.of(), headers, request);
        }

        List<JacksonException.Reference> path = je.getPath();
        if (isAlertsRequest(request)) {
            // Jackson stops at the first problem, so at most one error, e.g. a bad category.
            return badAlert(ex, List.of(path.isEmpty()
                    ? new AlertFieldError("", "must be a JSON object")
                    : new AlertFieldError(fieldPath(path), alertParseMessage(je))), headers, request);
        }
        if (path.isEmpty()) {
            return badRequest(ex, "Request body must be an event object or an array of events",
                    List.of(), headers, request);
        }

        // An array body's path starts with the element index; a bare object is event 0.
        boolean startsWithIndex = path.getFirst().getPropertyName() == null && path.getFirst().getIndex() >= 0;
        int index = startsWithIndex ? path.getFirst().getIndex() : 0;
        String field = fieldPath(startsWithIndex ? path.subList(1, path.size()) : path);
        String message = field.isEmpty() && je instanceof MismatchedInputException
                ? "must be a JSON object"
                : parseMessage(je);
        return badRequest(ex, "Request contains invalid events",
                List.of(new EventError(index, field, message)), headers, request);
    }

    /**
     * A required query parameter is missing, e.g. {@code from} on the stats summary. Spring stops at
     * the first missing one, so a single {@link QueryParamError} is reported, in the same shape as
     * the controller's own parameter checks.
     */
    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(
            MissingServletRequestParameterException ex, HttpHeaders headers, HttpStatusCode status,
            WebRequest request) {
        return badQuery(ex, List.of(new QueryParamError(ex.getParameterName(), "is required")), headers, request);
    }

    /**
     * A query parameter that could not be converted to its type, e.g. {@code limit=abc},
     * {@code category=xss} or {@code from=yesterday} on samples. Spring stops at the first one, so a
     * single {@link QueryParamError} is reported.
     */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (!(ex instanceof MethodArgumentTypeMismatchException mismatch)) {
            return super.handleTypeMismatch(ex, headers, status, request);
        }
        String name = queryParameterName(mismatch.getParameter());
        return badQuery(ex, List.of(new QueryParamError(name, typeMessage(mismatch.getRequiredType()))),
                headers, request);
    }

    /**
     * A read query (samples or timeseries) ran past its configured timeout and PostgreSQL cancelled it.
     * The same request would time out again, so there is no Retry-After: the client should narrow it.
     */
    @ExceptionHandler(ReadQueryTimeoutException.class)
    ResponseEntity<ProblemDetail> handleReadQueryTimeout(ReadQueryTimeoutException ex) {
        logger.warn("Read query timed out: " + ex.getMessage());
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Query took too long; narrow the filters or the time range");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    /**
     * The event queue had no room for the whole batch, usually because storage is slow or down.
     * Nothing was queued, so the client should retry the whole request. Returned as 503 because it
     * is a temporary problem on our side, not something wrong with the request.
     */
    @ExceptionHandler(EventPublishException.class)
    ResponseEntity<ProblemDetail> handleEventPublish(EventPublishException ex) {
        logger.warn("Ingest failed: " + ex.getMessage(), ex);
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Events could not be stored right now; retry the whole request");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                .body(body);
    }

    /**
     * The client used up its read budget for the current window
     * ({@link com.miniwsa.ratelimit.RateLimitInterceptor}). Not a problem on our side, so only DEBUG: a client
     * hammering the API would otherwise flood the log.
     */
    @ExceptionHandler(RateLimitExceededException.class)
    ResponseEntity<ProblemDetail> handleRateLimitExceeded(RateLimitExceededException ex) {
        logger.debug("Rate limit exceeded");
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                "Rate limit exceeded; try again later");
        body.setTitle("Too Many Requests");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(body);
    }

    private ResponseEntity<Object> badRequest(Exception ex, String detail, List<EventError> errors,
                                              HttpHeaders headers, WebRequest request) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        body.setProperty("errors", errors);
        return handleExceptionInternal(ex, body, headers, HttpStatus.BAD_REQUEST, request);
    }

    private ResponseEntity<Object> badQuery(Exception ex, List<QueryParamError> errors,
                                            HttpHeaders headers, WebRequest request) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid query parameters");
        body.setProperty("errors", errors);
        return handleExceptionInternal(ex, body, headers, HttpStatus.BAD_REQUEST, request);
    }

    private ResponseEntity<Object> badAlert(Exception ex, List<AlertFieldError> errors,
                                            HttpHeaders headers, WebRequest request) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, INVALID_ALERT_DETAIL);
        body.setProperty("errors", errors);
        return handleExceptionInternal(ex, body, headers, HttpStatus.BAD_REQUEST, request);
    }

    private static boolean isAlertsRequest(WebRequest request) {
        return request instanceof ServletWebRequest servlet
                && servlet.getRequest().getRequestURI().startsWith("/v1/alerts/");
    }

    /** Like {@link #parseMessage}, plus a short message for a number of the wrong type or size. */
    private static String alertParseMessage(JacksonException e) {
        if (e instanceof InputCoercionException) {
            return "must be an integer";
        }
        if (e instanceof MismatchedInputException mie && !(e instanceof InvalidFormatException ife
                && ife.getTargetType() != null && ife.getTargetType().isEnum())) {
            Class<?> target = mie.getTargetType();
            if (target == Integer.class || target == int.class) {
                return "must be an integer";
            }
            if (target == String.class) {
                return "must be a string";
            }
        }
        return parseMessage(e);
    }

    private static boolean isQueryParameter(ParameterValidationResult result) {
        return result.getMethodParameter().hasParameterAnnotation(RequestParam.class);
    }

    /** The name the client used: the one given in {@code @RequestParam}, else the Java parameter name. */
    private static String queryParameterName(MethodParameter parameter) {
        RequestParam annotation = parameter.getParameterAnnotation(RequestParam.class);
        if (annotation != null && !annotation.name().isEmpty()) {
            return annotation.name();
        }
        return Objects.requireNonNullElse(parameter.getParameterName(), "");
    }

    /** What a query parameter of this type must look like. */
    private static String typeMessage(Class<?> type) {
        if (type != null && type.isEnum()) {
            String allowed = Arrays.stream(type.getEnumConstants())
                    .map(Object::toString)
                    .collect(Collectors.joining(", "));
            return "must be one of [" + allowed + "]";
        }
        if (type == Instant.class || type == OffsetDateTime.class) {
            return ISO_TIMESTAMP_MESSAGE;
        }
        if (type == Long.class || type == long.class) {
            return "must be a positive integer";
        }
        return "must be an integer";
    }

    /** Builds a dotted path such as {@code rule.category} from where Jackson hit the problem. */
    private static String fieldPath(List<JacksonException.Reference> refs) {
        StringBuilder path = new StringBuilder();
        for (JacksonException.Reference ref : refs) {
            if (ref.getPropertyName() != null) {
                if (!path.isEmpty()) {
                    path.append('.');
                }
                path.append(ref.getPropertyName());
            } else if (ref.getIndex() >= 0) {
                path.append('[').append(ref.getIndex()).append(']');
            }
        }
        return path.toString();
    }

    /** Short, client-friendly messages for the common cases: bad enum values and bad timestamps. */
    private static String parseMessage(JacksonException e) {
        if (e instanceof InvalidFormatException ife) {
            Class<?> target = ife.getTargetType();
            if (target != null && target.isEnum()) {
                String allowed = Arrays.stream(target.getEnumConstants())
                        .map(Object::toString)
                        .collect(Collectors.joining(", "));
                return "must be one of [" + allowed + "]";
            }
            if (target == Instant.class) {
                return ISO_TIMESTAMP_MESSAGE;
            }
        }
        return e.getOriginalMessage();
    }
}
