package ru.longirun.gestalt.eval.extract;

import ru.longirun.gestalt.eval.ingest.RawMessage;

import java.util.List;

public interface FactExtractor {

    List<ExtractedFact> extract(List<RawMessage> batch) throws Exception;
}
