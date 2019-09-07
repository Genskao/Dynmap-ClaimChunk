package org.dynmap.claimchunk;

public class ClaimChunkUpdate implements Runnable {
    private final DynmapClaimChunkPlugin kernel;

    public ClaimChunkUpdate(final DynmapClaimChunkPlugin kernel) {
        this.kernel = kernel;
    }

    @Override
    public synchronized void run() {
        if (!kernel.isStop()) {
            kernel.updateClaimedChunk();
            kernel.scheduleSyncDelayedTask(this, kernel.getInterval());
        }
    }
}
