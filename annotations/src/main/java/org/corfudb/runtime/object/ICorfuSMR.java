package org.corfudb.runtime.object;

import org.corfudb.annotations.DontInstrument;
import org.corfudb.annotations.PassThrough;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** The interface for an object interfaced with SMR.
 * @param <T> The type of the underlying object.
 * Created by mwei on 11/10/16.
 */
public interface ICorfuSMR<T> extends AutoCloseable {
    /** The suffix for all precompiled SMR wrapper classes. */
    String CORFUSMR_SUFFIX = "$CORFUSMR";

    /** Get the proxy for this wrapper, to manage the state of the object.
     * @return The proxy for this wrapper. */
    default ICorfuSMRProxy<T> getCorfuSMRProxy() {
        throw new IllegalStateException("ObjectAnnotationProcessor Issue.");
    }

    /**
     * Set the proxy for this wrapper, to manage the state of the object.
     * @param proxy The proxy to set for this wrapper.
     * @param <R> The type used for managing underlying versions.
     */
    default <R> void setProxy$CORFUSMR(ICorfuSMRProxy<R> proxy) {
        throw new IllegalStateException("ObjectAnnotationProcessor Issue.");
    }

    /**
     * Get a map from strings (function names) to SMR upcalls.
     * @param <R> The return type for ICorfuSMRUpcallTargets
     * @return A map from function names to SMR upcalls
     */
    default <R> Map<String, ICorfuSMRUpcallTarget<R>> getSMRUpcallMap() {
        throw new IllegalStateException("ObjectAnnotationProcessor Issue.");
    }

    /** Return the stream ID that this object belongs to.
     * @return The stream ID this object belongs to. */
    default UUID getCorfuStreamID() {
        return getCorfuSMRProxy().getStreamID();
    }

    /**
     *
     * @param version version
     * @return ISMRSnapshot
     */
    default ISMRSnapshot<T> getSnapshot(VersionedObjectIdentifier version) {
        throw new IllegalStateException("ObjectAnnotationProcessor Issue.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    void close();
}
