package com.stellartrack.watch.domain;

/** Lifecycle of a Watch session. Only one session is ACTIVE at a time. */
public enum WatchSessionStatus {
    ACTIVE,
    STOPPED,
    FAILED
}
