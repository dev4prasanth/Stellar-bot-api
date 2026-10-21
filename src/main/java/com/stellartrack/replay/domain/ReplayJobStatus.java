package com.stellartrack.replay.domain;

/**
 * UPLOADED → VALIDATING → (VALIDATION_FAILED | BLOCKED | RUNNING → COMPLETED | FAILED).
 * BLOCKED means the file is stored and well-formed but evaluation cannot run until an approved historical
 * input schema and evaluation engine are supplied.
 */
public enum ReplayJobStatus {
    UPLOADED,
    VALIDATING,
    VALIDATION_FAILED,
    BLOCKED,
    RUNNING,
    COMPLETED,
    FAILED
}
