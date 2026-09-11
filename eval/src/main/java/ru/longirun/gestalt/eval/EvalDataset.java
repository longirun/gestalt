package ru.longirun.gestalt.eval;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Загрузка датасета точек (JSON Lines, ADR 28 §3.2). */
public final class EvalDataset {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EvalDataset() {
    }

    public static List<EvalPoint> load(Path file) throws IOException {
        if (!Files.exists(file)) {
            throw new IllegalStateException("dataset file not found: " + file
                    + " (параметр dataset.file в local.properties)");
        }
        List<EvalPoint> points = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (String line : Files.readAllLines(file)) {
            if (line.isBlank()) {
                continue;
            }
            EvalPoint point = MAPPER.readValue(line, EvalPoint.class);
            if (!ids.add(point.id())) {
                throw new IllegalStateException("duplicate point id in dataset: " + point.id());
            }
            points.add(point);
        }
        return points;
    }
}
