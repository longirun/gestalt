package ru.longirun.gestalt.spike.ingest;

import java.time.OffsetDateTime;

public record RawMessage(
        long id,
        String sessionName,
        String peerName,
        String content,
        int tokenCount,
        OffsetDateTime createdAt) {
}
