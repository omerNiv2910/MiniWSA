package com.miniwsa.api;

import com.miniwsa.alerts.AlertDefinition;
import com.miniwsa.model.Category;
import com.miniwsa.alerts.AlertService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AlertingController.class)
class AlertingControllerTest {

    private static final String URL = "/v1/alerts/define";
    private static final String CATEGORIES =
            "must be one of [INJECTION, XSS, PROTOCOL_VIOLATION, DATA_LEAKAGE, BOT, DOS, RATE_LIMIT]";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AlertService alertService;

    // ---- success ----

    @Test
    void storesTheDefinitionAndReturnsItWithItsId() throws Exception {
        stubStore();

        define("""
                {"name": "SQLi burst", "category": "INJECTION", "events": 100, "windowMinutes": 5}
                """)
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {"status": 201, "message": "Alert defined", "alert":
                          {"id": 7, "name": "SQLi burst", "category": "INJECTION", "events": 100, "windowMinutes": 5}}
                        """, JsonCompareMode.STRICT));

        verify(alertService).define(new AlertDefinition(null, "SQLi burst", Category.INJECTION, 100, 5));
    }

    @Test
    void acceptsTheBoundaryValues() throws Exception {
        stubStore();

        define(body("\"BOT\"", "0", "1")).andExpect(status().isCreated());
        define(body("\"BOT\"", "0", "1440")).andExpect(status().isCreated());

        verify(alertService).define(new AlertDefinition(null, "rule", Category.BOT, 0, 1));
        verify(alertService).define(new AlertDefinition(null, "rule", Category.BOT, 0, 1440));
    }

    @Test
    void ignoresAClientIdAndUnknownProperties() throws Exception {
        stubStore();

        define("""
                {"id": 99, "name": "rule", "category": "XSS", "events": 3, "windowMinutes": 10, "configId": 42}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.alert.id").value(7));

        verify(alertService).define(new AlertDefinition(null, "rule", Category.XSS, 3, 10));
    }

    // ---- field validation ----

    @Test
    void everyMissingFieldIsReportedTogetherInFieldOrder() throws Exception {
        define("{}")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().json("""
                        {"status": 400, "detail": "Invalid alert definition", "errors": [
                          {"field": "name", "message": "is required"},
                          {"field": "category", "message": "is required"},
                          {"field": "events", "message": "is required"},
                          {"field": "windowMinutes", "message": "is required"}
                        ]}
                        """));
        verifyNoInteractions(alertService);
    }

    @Test
    void everyInvalidFieldIsReportedTogether() throws Exception {
        define("""
                {"name": "", "category": "XSS", "events": -5, "windowMinutes": 0}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(3))
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.errors[1].field").value("events"))
                .andExpect(jsonPath("$.errors[1].message").value("must be 0 or more"))
                .andExpect(jsonPath("$.errors[2].field").value("windowMinutes"))
                .andExpect(jsonPath("$.errors[2].message").value("must be between 1 and 1440"));
        verifyNoInteractions(alertService);
    }

    @Test
    void rejectsABlankName() throws Exception {
        expectSingleError("""
                {"name": "  ", "category": "XSS", "events": 3, "windowMinutes": 10}
                """, "name", "is required");
    }

    @Test
    void rejectsANameOver128Characters() throws Exception {
        expectSingleError("""
                {"name": "%s", "category": "XSS", "events": 3, "windowMinutes": 10}
                """.formatted("a".repeat(129)), "name", "must be at most 128 characters");
    }

    @Test
    void anExplicitNullIsMissing() throws Exception {
        expectSingleError(body("null", "3", "10"), "category", "is required");
    }

    @Test
    void rejectsNegativeEvents() throws Exception {
        expectSingleError(body("\"XSS\"", "-1", "10"), "events", "must be 0 or more");
    }

    @Test
    void rejectsAWindowOfZeroMinutes() throws Exception {
        expectSingleError(body("\"XSS\"", "3", "0"), "windowMinutes", "must be between 1 and 1440");
    }

    @Test
    void rejectsAWindowOverOneDay() throws Exception {
        expectSingleError(body("\"XSS\"", "3", "1441"), "windowMinutes", "must be between 1 and 1440");
    }

    // ---- unreadable bodies (Jackson stops at the first problem) ----

    @Test
    void rejectsAnUnknownCategory() throws Exception {
        expectSingleError(body("\"SQLI\"", "3", "10"), "category", CATEGORIES);
    }

    @Test
    void categoryIsCaseSensitive() throws Exception {
        expectSingleError(body("\"injection\"", "3", "10"), "category", CATEGORIES);
    }

    @Test
    void rejectsEventsThatIsNotANumber() throws Exception {
        expectSingleError(body("\"XSS\"", "\"ten\"", "10"), "events", "must be an integer");
    }

    @Test
    void rejectsWindowMinutesBeyondTheIntegerRange() throws Exception {
        expectSingleError(body("\"XSS\"", "3", "99999999999"), "windowMinutes", "must be an integer");
    }

    @Test
    void rejectsAnArrayBody() throws Exception {
        expectSingleError("[]", "", "must be a JSON object");
    }

    @Test
    void rejectsMalformedJson() throws Exception {
        define("{\"name\": ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Request body is not valid JSON"));
        verifyNoInteractions(alertService);
    }

    @Test
    void rejectsAnEmptyBody() throws Exception {
        define("")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
        verifyNoInteractions(alertService);
    }

    @Test
    void rejectsANonJsonContentType() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType());
        verifyNoInteractions(alertService);
    }

    // ---- helpers ----

    private ResultActions define(String json) throws Exception {
        return mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    /** A body named "rule" with the given raw JSON values. */
    private static String body(String category, String events, String windowMinutes) {
        return """
                {"name": "rule", "category": %s, "events": %s, "windowMinutes": %s}
                """.formatted(category, events, windowMinutes);
    }

    private void expectSingleError(String json, String field, String message) throws Exception {
        define(json)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().json("""
                        {"status": 400, "detail": "Invalid alert definition",
                         "errors": [{"field": "%s", "message": "%s"}]}
                        """.formatted(field, message)));
        verifyNoInteractions(alertService);
    }

    /** The store hands back what it was given, with id 7. */
    private void stubStore() {
        when(alertService.define(any())).thenAnswer(inv -> inv.<AlertDefinition>getArgument(0).withId(7));
    }
}
