package com.stellartrack.replay.domain;

import java.nio.file.Path;
import java.util.Map;

/**
 * Port for an approved historical evaluation engine. No implementation is registered: the historical input
 * schema and evaluation rules have not been supplied. An implementation must be side-effect free and must not
 * depend on the order module (replay is isolated from live order submission).
 */
public interface ReplayEvaluationEngine {

    String version();

    /** Validates the file against the approved schema; returns row-level errors (empty when valid). */
    java.util.List<String> validate(Path file);

    /** Evaluates the file and returns a reproducible result document. */
    Map<String, Object> evaluate(Path file, ReplayProgress progress);

    interface ReplayProgress {
        void update(int processedRows, int totalRows);
    }
}
