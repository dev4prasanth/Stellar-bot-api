package com.stellartrack.configuration.domain;

import java.util.Map;
import java.util.Set;

/** Automation lifecycle of a configuration with its allowed transitions. */
public enum OperationalState {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    ERROR;

    private static final Map<OperationalState, Set<OperationalState>> ALLOWED = Map.of(
            STOPPED, Set.of(STARTING),
            STARTING, Set.of(RUNNING, ERROR, STOPPED),
            RUNNING, Set.of(STOPPING, ERROR),
            STOPPING, Set.of(STOPPED, ERROR),
            ERROR, Set.of(STOPPED, STARTING));

    public boolean canTransitionTo(OperationalState target) {
        return ALLOWED.get(this).contains(target);
    }
}
