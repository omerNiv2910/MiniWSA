package com.miniwsa.samples;

import com.miniwsa.api.InvalidQueryParametersException;
import com.miniwsa.api.QueryParamError;
import com.miniwsa.model.Action;
import com.miniwsa.model.Category;
import com.miniwsa.storage.EventStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * The logic behind {@code /v1/events/samples}. The parameters arrive already bound and range-checked by
 * Spring; this checks that {@code from} is before {@code to} (it involves two parameters) and reads the
 * page from {@link EventStore}.
 */
@Service
public class SamplesService {

    private final EventStore eventStore;

    public SamplesService(EventStore eventStore) {
        this.eventStore = eventStore;
    }

    public SampleResponse samples(Long configId, OffsetDateTime from, OffsetDateTime to,
                                  Category category, Action action, int limit, int offset) {
        Instant fromInstant = from == null ? null : from.toInstant();
        Instant toInstant = to == null ? null : to.toInstant();
        if (fromInstant != null && toInstant != null && !fromInstant.isBefore(toInstant)) {
            throw new InvalidQueryParametersException(List.of(new QueryParamError("from", "must be before to")));
        }
        SampleQuery query = new SampleQuery(configId, fromInstant, toInstant, category, action, limit, offset);
        return eventStore.findSamples(query);
    }
}
