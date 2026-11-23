package com.miniwsa.stats;

import com.miniwsa.model.Action;
import com.miniwsa.model.Category;
import com.miniwsa.stats.StatsSummary.AttackerStats;
import com.miniwsa.stats.StatsSummary.CategoryStats;
import com.miniwsa.stats.StatsSummary.PathStats;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StatsSummaryJsonTest {

    private static final JsonMapper mapper = JsonMapper.builder().build();
    private static final Instant FROM = Instant.parse("2026-05-20T14:00:00Z");
    private static final Instant TO = Instant.parse("2026-05-20T15:00:00Z");

    @Test
    void writesTheDocumentedShape() {
        // Not in enum order on purpose: the JSON keeps the order the maps were given in.
        Map<Category, CategoryStats> byCategory = new LinkedHashMap<>();
        byCategory.put(Category.INJECTION, new CategoryStats(450, new BigDecimal("72.3")));
        byCategory.put(Category.BOT, new CategoryStats(380, new BigDecimal("45.1")));
        Map<Action, Long> byAction = new LinkedHashMap<>();
        byAction.put(Action.DENY, 890L);
        byAction.put(Action.MONITOR, 433L);
        byAction.put(Action.ALERT, 200L);
        List<AttackerStats> topAttackers = List.of(new AttackerStats("203.0.113.42", 87, new BigDecimal("81.2")));
        List<PathStats> topPaths = List.of(new PathStats("/api/v1/login", 234));

        String json = mapper.writeValueAsString(
                new StatsSummary(14227L, FROM, TO, 1523, byCategory, byAction, topAttackers, topPaths));

        assertThat(json).isEqualTo("""
                {"configId":14227,\
                "timeRange":{"from":"2026-05-20T14:00:00Z","to":"2026-05-20T15:00:00Z"},\
                "totalEvents":1523,\
                "byCategory":{"INJECTION":{"count":450,"avgThreatScore":72.3},"BOT":{"count":380,"avgThreatScore":45.1}},\
                "byAction":{"DENY":890,"MONITOR":433,"ALERT":200},\
                "topAttackers":[{"clientIp":"203.0.113.42","count":87,"avgThreatScore":81.2}],\
                "topTargetedPaths":[{"path":"/api/v1/login","count":234}]}""");
    }

    @Test
    void leavesOutConfigIdWhenNullButKeepsNullAverages() {
        String json = mapper.writeValueAsString(new StatsSummary(null, FROM, TO, 1,
                Map.of(Category.DOS, new CategoryStats(1, null)), Map.of(Action.DENY, 1L),
                List.of(new AttackerStats("10.0.0.1", 1, null)), List.of(new PathStats("/", 1))));

        assertThat(json).doesNotContain("configId")
                .contains("\"DOS\":{\"count\":1,\"avgThreatScore\":null}")
                .contains("{\"clientIp\":\"10.0.0.1\",\"count\":1,\"avgThreatScore\":null}");
    }

    @Test
    void keepsOneDecimalOnWholeAverages() {
        String json = mapper.writeValueAsString(new StatsSummary(1L, FROM, TO, 1,
                Map.of(Category.BOT, new CategoryStats(1, new BigDecimal("75.0"))), Map.of(Action.DENY, 1L),
                List.of(new AttackerStats("10.0.0.1", 1, new BigDecimal("75.0"))), List.of(new PathStats("/", 1))));

        assertThat(json).contains("\"BOT\":{\"count\":1,\"avgThreatScore\":75.0}")
                .contains("\"clientIp\":\"10.0.0.1\",\"count\":1,\"avgThreatScore\":75.0");
    }

    @Test
    void emptyWindowWritesEmptyObjectsAndLists() {
        String json = mapper.writeValueAsString(
                new StatsSummary(1L, FROM, TO, 0, Map.of(), Map.of(), List.of(), List.of()));

        assertThat(json).contains(
                "\"totalEvents\":0,\"byCategory\":{},\"byAction\":{},\"topAttackers\":[],\"topTargetedPaths\":[]");
    }
}
