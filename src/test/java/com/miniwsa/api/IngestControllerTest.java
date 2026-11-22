package com.miniwsa.api;

import com.miniwsa.ingest.IngestService;
import com.miniwsa.model.SecurityEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(IngestController.class)
class IngestControllerTest {

    private static final String EVENT = """
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

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private IngestService ingestService;

    @BeforeEach
    void echoIngestedEvents() {
        when(ingestService.ingest(anyList())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void singleEventArrayIsAccepted() throws Exception {
        mvc.perform(post("/v1/events/ingest").contentType(MediaType.APPLICATION_JSON).content(array(EVENT)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accepted").value(1));

        List<SecurityEvent> ingested = captureIngested();
        assertThat(ingested).hasSize(1);
        assertThat(ingested.getFirst().eventId()).isEqualTo("evt-00132");
    }

    @Test
    void batchIsAccepted() throws Exception {
        String batch = array(EVENT, EVENT.replace("evt-00132", "evt-00133"));

        mvc.perform(post("/v1/events/ingest").contentType(MediaType.APPLICATION_JSON).content(batch))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accepted").value(2));

        assertThat(captureIngested()).extracting(SecurityEvent::eventId)
                .containsExactly("evt-00132", "evt-00133");
    }

    @Test
    void singleEventObjectIsAccepted() throws Exception {
        mvc.perform(post("/v1/events/ingest").contentType(MediaType.APPLICATION_JSON).content(EVENT))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accepted").value(1));

        assertThat(captureIngested()).extracting(SecurityEvent::eventId).containsExactly("evt-00132");
    }

    @Test
    void singleEventObjectValidationErrorHasIndexZero() throws Exception {
        String event = EVENT.replace("\"clientIp\": \"203.0.113.42\",", "");

        mvc.perform(post("/v1/events/ingest").contentType(MediaType.APPLICATION_JSON).content(event))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].index").value(0))
                .andExpect(jsonPath("$.errors[0].field").value("clientIp"));

        verify(ingestService, never()).ingest(any());
    }

    @Test
    void singleEventObjectParseErrorHasIndexZero() throws Exception {
        String event = EVENT.replace("\"INJECTION\"", "\"SQLI\"");

        mvc.perform(post("/v1/events/ingest").contentType(MediaType.APPLICATION_JSON).content(event))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].index").value(0))
                .andExpect(jsonPath("$.errors[0].field").value("rule.category"));
    }

    @Test
    void missingFieldIsRejectedWithItsIndex() throws Exception {
        String batch = array(EVENT, EVENT.replace("\"clientIp\": \"203.0.113.42\",", ""));

        mvc.perform(post("/v1/events/ingest").contentType(MediaType.APPLICATION_JSON).content(batch))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].index").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("clientIp"));

        verify(ingestService, never()).ingest(any());
    }

    @Test
    void nestedValidationErrorReportsNestedPath() throws Exception {
        String batch = array(EVENT.replace("\"name\": \"SQL_INJECTION\"", "\"name\": \"\""));

        mvc.perform(post("/v1/events/ingest").contentType(MediaType.APPLICATION_JSON).content(batch))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("rule.name"));
    }

    @Test
    void badEnumValueIsRejectedWithItsIndex() throws Exception {
        String batch = array(EVENT, EVENT.replace("\"DENY\"", "\"BLOCK\""));

        mvc.perform(post("/v1/events/ingest").contentType(MediaType.APPLICATION_JSON).content(batch))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].index").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("action"))
                .andExpect(jsonPath("$.errors[0].message").value("must be one of [DENY, ALERT, MONITOR]"));

        verify(ingestService, never()).ingest(any());
    }

    @Test
    void badNestedEnumValueReportsNestedPath() throws Exception {
        String batch = array(EVENT.replace("\"INJECTION\"", "\"SQLI\""));

        mvc.perform(post("/v1/events/ingest").contentType(MediaType.APPLICATION_JSON).content(batch))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("rule.category"));
    }

    @Test
    void badTimestampIsRejected() throws Exception {
        String batch = array(EVENT.replace("2026-05-20T14:32:10Z", "20/05/2026 14:32"));

        mvc.perform(post("/v1/events/ingest").contentType(MediaType.APPLICATION_JSON).content(batch))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("timestamp"))
                .andExpect(jsonPath("$.errors[0].message")
                        .value("must be an ISO-8601 timestamp, e.g. 2026-05-20T14:32:10Z"));
    }

    @Test
    void allValidationErrorsInABatchAreReported() throws Exception {
        String batch = array(
                EVENT.replace("\"clientIp\": \"203.0.113.42\",", ""),
                EVENT,
                EVENT.replace("\"statusCode\": 403", "\"statusCode\": 42"));

        mvc.perform(post("/v1/events/ingest").contentType(MediaType.APPLICATION_JSON).content(batch))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasSize(2)))
                .andExpect(jsonPath("$.errors[0].index").value(0))
                .andExpect(jsonPath("$.errors[0].field").value("clientIp"))
                .andExpect(jsonPath("$.errors[1].index").value(2))
                .andExpect(jsonPath("$.errors[1].field").value("statusCode"));

        verify(ingestService, never()).ingest(any());
    }

    @Test
    void emptyArrayIsRejected() throws Exception {
        mvc.perform(post("/v1/events/ingest").contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Request must contain at least one event"));
    }

    @Test
    void nonObjectBodyIsRejected() throws Exception {
        mvc.perform(post("/v1/events/ingest").contentType(MediaType.APPLICATION_JSON).content("\"hello\""))
                .andExpect(status().isBadRequest());

        verify(ingestService, never()).ingest(any());
    }

    @Test
    void nonObjectArrayItemIsRejected() throws Exception {
        mvc.perform(post("/v1/events/ingest").contentType(MediaType.APPLICATION_JSON).content(array(EVENT, "42")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].index").value(1))
                .andExpect(jsonPath("$.errors[0].message").value("must be a JSON object"));
    }

    @Test
    void nullArrayItemIsRejected() throws Exception {
        mvc.perform(post("/v1/events/ingest").contentType(MediaType.APPLICATION_JSON).content(array(EVENT, "null")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].index").value(1))
                .andExpect(jsonPath("$.errors[0].field").value(""));

        verify(ingestService, never()).ingest(any());
    }

    @Test
    void malformedJsonIsRejected() throws Exception {
        mvc.perform(post("/v1/events/ingest").contentType(MediaType.APPLICATION_JSON).content("[{ not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Request body is not valid JSON"));
    }

    private static String array(String... events) {
        return "[" + String.join(",", events) + "]";
    }

    @SuppressWarnings("unchecked")
    private List<SecurityEvent> captureIngested() {
        ArgumentCaptor<List<SecurityEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(ingestService).ingest(captor.capture());
        return captor.getValue();
    }
}
