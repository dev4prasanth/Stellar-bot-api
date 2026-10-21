package com.stellartrack.common.capability;

/**
 * Integration status vocabulary used across the API, UI and documentation.
 * <ul>
 *   <li>{@code IMPLEMENTED}: code complete and configured, not yet verified against the real provider.</li>
 *   <li>{@code VERIFIED}: a successful call to the real provider has been observed in this runtime.</li>
 *   <li>{@code DISABLED}: implemented but switched off by configuration (for example missing credentials).</li>
 *   <li>{@code BLOCKED}: cannot be implemented until a missing contract or approved rule is supplied.</li>
 * </ul>
 */
public enum CapabilityState {
    IMPLEMENTED,
    VERIFIED,
    DISABLED,
    BLOCKED
}
