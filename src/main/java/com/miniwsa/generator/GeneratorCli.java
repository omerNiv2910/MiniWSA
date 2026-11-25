package com.miniwsa.generator;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.miniwsa.model.SecurityEvent;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Random;

/**
 * Command-line entry point of the data generator. It runs on its own, without the Spring application, and never
 * touches the API or the database.
 *
 * <p>Generates {@code --count} events and writes them to the {@code --output} file as one JSON array,
 * a body {@code POST /v1/events/ingest} accepts as-is. Both options have defaults, so a bare run works.
 *
 * <p>Exit codes: 0 success, 1 the file could not be written, 2 the command line was wrong.
 */
public final class GeneratorCli {

    static final int OK = 0;
    static final int FAILED = 1;
    static final int USAGE = 2;

    static final int DEFAULT_COUNT = 1000;
    static final String DEFAULT_OUTPUT = "data/events.json";

    static final String USAGE_TEXT = """
            Usage: GeneratorCli [--count <n>] [--output <file>]

            Generates random security events over the last 7 days, including attack waves (bursts of events
            from the same IP hitting the same path), as a JSON array for POST /v1/events/ingest.

              --count <n>       number of events, 1 to 20000 (default 1000)
              --output <file>   file to write the events to; missing folders are created
                                (default data/events.json)
              --help            show this text
            """;

    /** Leaves out null fields, so the server-set receivedAt, attackType and threatScore are never written. */
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .changeDefaultPropertyInclusion(incl -> JsonInclude.Value.construct(
                    JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
            .build();

    private GeneratorCli() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /** Does the whole run and returns the exit code. */
    static int run(String[] args, PrintStream out, PrintStream err) {
        int count = DEFAULT_COUNT;
        String outFile = DEFAULT_OUTPUT;
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--help" -> {
                        out.print(USAGE_TEXT);
                        return OK;
                    }
                    case "--count" -> count = parseCount(optionValue(args, i++));
                    case "--output" -> outFile = optionValue(args, i++);
                    default -> throw new IllegalArgumentException("unknown option: " + args[i]);
                }
            }
        } catch (IllegalArgumentException e) {
            err.println("Error: " + e.getMessage());
            err.println();
            err.print(USAGE_TEXT);
            return USAGE;
        }

        List<SecurityEvent> events = EventGenerator.generate(count, new Random(),
                Instant.now().truncatedTo(ChronoUnit.MILLIS));
        String json = toJson(events);
        try {
            Path path = Path.of(outFile).toAbsolutePath();
            Files.createDirectories(path.getParent());
            Files.writeString(path, json, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            err.println("Error: could not write " + outFile + ": " + e.getMessage());
            return FAILED;
        }
        out.println("wrote " + events.size() + " events to " + outFile);
        return OK;
    }

    /** The events as one JSON array, one event per line. */
    static String toJson(List<SecurityEvent> events) {
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < events.size(); i++) {
            sb.append("  ").append(MAPPER.writeValueAsString(events.get(i)));
            sb.append(i < events.size() - 1 ? ",\n" : "\n");
        }
        return sb.append("]\n").toString();
    }

    /** The value after the option at {@code i}. */
    private static String optionValue(String[] args, int i) {
        if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
            throw new IllegalArgumentException(args[i] + " needs a value");
        }
        return args[i + 1];
    }

    private static int parseCount(String raw) {
        int count;
        try {
            count = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--count must be a whole number, got '" + raw + "'");
        }
        if (count < 1 || count > EventGenerator.MAX_COUNT) {
            throw new IllegalArgumentException("--count must be between 1 and " + EventGenerator.MAX_COUNT
                    + ", got " + count);
        }
        return count;
    }
}
