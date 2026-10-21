package com.stellartrack.watch.domain;

/** Run state of one configuration inside a Watch session. */
public enum WatchConfigurationState {
    RUNNING,
    /** Paused by the administrator; can be resumed. */
    PAUSED,
    /** Ended by a stop rule (stop-loss, stop-profit, entry limits) or by the session ending. */
    STOPPED
}
