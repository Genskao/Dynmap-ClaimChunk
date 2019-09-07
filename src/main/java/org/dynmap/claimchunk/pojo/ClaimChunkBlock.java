package org.dynmap.claimchunk.pojo;

public class ClaimChunkBlock {
    private final int x;
    private final int z;

    public ClaimChunkBlock(final int x, final int z) {
        this.x = x;
        this.z = z;
    }

    public int getX() {
        return x;
    }

    public int getZ() {
        return z;
    }
}
