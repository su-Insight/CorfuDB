package org.corfudb.runtime.object;

import com.google.common.base.Preconditions;
import lombok.extern.slf4j.Slf4j;
import org.corfudb.common.metrics.micrometer.MicroMeterUtils;
import org.corfudb.runtime.CorfuRuntime;
import org.corfudb.runtime.exceptions.TrimmedException;
import org.corfudb.runtime.exceptions.unrecoverable.UnrecoverableCorfuError;
import org.corfudb.util.Utils;

import javax.annotation.Nonnull;
import java.util.function.Supplier;

/**
 * An implementation of a versioned SMR object that does not perform any caching
 * with respect to previously generated versions. In other words, only the most recent
 * versioned object and corresponding snapshot is maintained.
 * @param <S> The type of SMR object that provides snapshot generation capabilities.
 */
@Slf4j
public class VersionedObject<S extends SnapshotGenerator<S>> extends AbstractVersionedObject<S> {

    public VersionedObject(@Nonnull CorfuRuntime corfuRuntime, @Nonnull Supplier<S> newObjectFn,
            @Nonnull StreamViewSMRAdapter smrStream, @Nonnull ICorfuSMR wrapperObject) {
        super(corfuRuntime, newObjectFn, smrStream, wrapperObject);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected ISMRSnapshot<S> retrieveSnapshotUnsafe(@Nonnull VersionedObjectIdentifier voId) {
        if (voId.getVersion() == materializedUpTo) {
            return currentSnapshot;
        }

        throw new TrimmedException(voId.getVersion(),
                String.format("Trimmed address %s is no longer available. StreamAddressSpace: %s.",
                        voId.getVersion(), addressSpace.toString())
        );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void syncStreamUnsafe(long timestamp) {
        if (log.isTraceEnabled()) {
            log.trace("Sync[{}] to {}", Utils.toReadableId(getID()), timestamp);
        }

        Runnable syncStreamRunnable = () -> {
            smrStream.streamUpToInList(timestamp)
                    .forEachOrdered(addressUpdates -> {
                        try {
                            // Apply all updates in a MultiSMREntry, which is treated as one version.
                            final long globalAddress = addressUpdates.getGlobalAddress();

                            // The globalAddress can be equal to materializedUpTo when processing checkpoint
                            // entries that consist of multiple continuation entries. These will all share the
                            // globalAddress of the no-op operation. There is no correctness issue by prematurely
                            // updating version information, as optimistic reads will be invalid.
                            Preconditions.checkState(globalAddress >= materializedUpTo,
                                    "globalAddress %s not >= materialized %s", globalAddress, materializedUpTo);

                            // Perform similar validation for resolvedUpTo.
                            if (globalAddress < resolvedUpTo) {
                                log.warn("Sync[{}]: globalAddress {} not >= resolved {}",
                                        Utils.toReadableId(getID()), globalAddress, resolvedUpTo);
                                throw new TrimmedException();
                            }

                            // In the case where addressUpdates corresponds to a HOLE, getSmrEntryList() will
                            // produce an empty list and the below will be a no-op. This means that there can
                            // be multiple versions that correspond to the same exact object.
                            addressUpdates.getSmrEntryList().forEach(this::applyUpdateUnsafe);
                            addressSpace.addAddress(globalAddress);
                            materializedUpTo = globalAddress;
                            resolvedUpTo = globalAddress;
                        } catch (TrimmedException e) {
                            // The caller catches this TrimmedException and resets the object before retrying.
                            throw e;
                        } catch (Exception e) {
                            log.error("Sync[{}] couldn't execute upcall due to {}", Utils.toReadableId(getID()), e);
                            throw new UnrecoverableCorfuError(e);
                        }
                    });

            // Release resources associated the previous snapshot and generate the new one.
            currentSnapshot.release();
            currentSnapshot = currentObject.getSnapshot(new VersionedObjectIdentifier(getID(), materializedUpTo));
        };


        MicroMeterUtils.time(syncStreamRunnable, "mvo.sync.timer", STREAM_ID_TAG_NAME, getID().toString());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MVOCache<S> getMvoCache() {
        throw new UnsupportedOperationException();
    }
}
