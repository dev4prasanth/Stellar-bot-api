package com.stellartrack.messaging.contract;

import java.util.List;

/** Test-only factory for the registry's package-private constructor. */
public final class LayerContractRegistryAccess {

    private LayerContractRegistryAccess() {
    }

    public static LayerContractRegistry of(List<LayerContract<?>> contracts) {
        return new LayerContractRegistry(contracts);
    }
}
