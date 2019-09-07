package org.dynmap.claimchunk.pojo;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

public class ClaimChunkBlocks {
    private final Map<String, LinkedList<ClaimChunkBlock>> blocks = new HashMap<>();

    public Map<String, LinkedList<ClaimChunkBlock>> getBlocks() {
        return blocks;
    }

    public void clear() {
        blocks.clear();
    }
}
