package com.stellartrack.common.capability;

import java.util.List;

/** Implemented by each module that owns one or more {@link Capability capabilities}. */
public interface CapabilityProvider {

    List<CapabilityReport> capabilities();
}
