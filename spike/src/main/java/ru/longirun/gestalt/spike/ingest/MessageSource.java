package ru.longirun.gestalt.spike.ingest;

import java.util.List;

public interface MessageSource {

    List<RawMessage> read() throws Exception;
}
