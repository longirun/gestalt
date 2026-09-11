package ru.longirun.gestalt.eval.ingest;

import java.util.List;

public interface MessageSource {

    List<RawMessage> read() throws Exception;
}
