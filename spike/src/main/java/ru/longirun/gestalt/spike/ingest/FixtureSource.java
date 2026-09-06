package ru.longirun.gestalt.spike.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** Синтетический контрольный срез (ADR 24 §8.А) — публичная замена живых данных. */
public final class FixtureSource implements MessageSource {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules();

    private final Path fixture;

    public FixtureSource(Path fixture) {
        this.fixture = fixture;
    }

    @Override
    public List<RawMessage> read() throws Exception {
        List<RawMessage> messages = new ArrayList<>();
        for (String line : Files.readAllLines(fixture)) {
            if (line.isBlank()) {
                continue;
            }
            var node = MAPPER.readTree(line);
            String createdAt = node.path("created_at").asText("");
            messages.add(new RawMessage(
                    node.get("id").asLong(),
                    node.get("session_name").asText(),
                    node.get("peer_name").asText(),
                    node.get("content").asText(),
                    node.get("token_count").asInt(),
                    createdAt.isEmpty() ? null : OffsetDateTime.parse(createdAt)));
        }
        return messages;
    }
}
