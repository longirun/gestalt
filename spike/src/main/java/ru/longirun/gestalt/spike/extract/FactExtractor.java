package ru.longirun.gestalt.spike.extract;

import ru.longirun.gestalt.spike.ingest.RawMessage;

import java.util.List;

public interface FactExtractor {

    List<ExtractedFact> extract(List<RawMessage> batch) throws Exception;
}
