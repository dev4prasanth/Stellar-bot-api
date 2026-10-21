package com.stellartrack.order.application;

import java.util.Optional;
import java.util.UUID;

/**
 * Supplies the active automation session of a configuration, so session-scoped limits and stops apply to
 * approved proposals as well. Implemented by the automation module (dependency inversion).
 */
public interface AutomationSessionProvider {

    Optional<PlacementCommand.AutomationSession> activeSession(UUID configurationId);
}
