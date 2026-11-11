package com.springai.fundamentals.observation;

import io.micrometer.common.KeyValues;
import org.jspecify.annotations.NullMarked;
import org.springframework.ai.vectorstore.observation.DefaultVectorStoreObservationConvention;
import org.springframework.ai.vectorstore.observation.VectorStoreObservationContext;

/** Keeps vector-search timings and settings observable without exporting query or filter contents. */
public final class PrivacyPreservingVectorStoreObservationConvention
        extends DefaultVectorStoreObservationConvention {

    @Override
    protected @NullMarked KeyValues queryContent(KeyValues keyValues, VectorStoreObservationContext context) {
        return keyValues;
    }

    @Override
    protected @NullMarked KeyValues metadataFilter(KeyValues keyValues, VectorStoreObservationContext context) {
        return keyValues;
    }
}
