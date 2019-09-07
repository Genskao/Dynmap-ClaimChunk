package org.dynmap.claimchunk.area;

import org.dynmap.claimchunk.DynmapClaimChunkPlugin;
import org.dynmap.claimchunk.TileFlags;
import org.dynmap.claimchunk.commons.Direction;
import org.dynmap.claimchunk.pojo.ClaimChunkBlock;
import org.dynmap.markers.AreaMarker;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Map;

import static org.dynmap.claimchunk.commons.Constant.MAX_BLOCK_SIZE;

public class AreaCommon {


    /**
     * Find all contiguous blocks, set in target and clear in source
     */
    public static int floodFillTarget(TileFlags src, TileFlags dest, int x, int y) {
        int cnt = 0;
        ArrayDeque<int[]> stack = new ArrayDeque<int[]>();
        stack.push(new int[]{x, y});

        while (!stack.isEmpty()) {
            int[] nxt = stack.pop();
            x = nxt[0];
            y = nxt[1];
            if (src.getFlag(x, y)) { /* Set in src */
                src.setFlag(x, y, false);   /* Clear source */
                dest.setFlag(x, y, true);   /* Set in destination */
                cnt++;
                if (src.getFlag(x + 1, y))
                    stack.push(new int[]{x + 1, y});
                if (src.getFlag(x - 1, y))
                    stack.push(new int[]{x - 1, y});
                if (src.getFlag(x, y + 1))
                    stack.push(new int[]{x, y + 1});
                if (src.getFlag(x, y - 1))
                    stack.push(new int[]{x, y - 1});
            }
        }
        return cnt;
    }

    public static void addStyle(final Map<String, AreaStyle> cusstyle, final AreaStyle defstyle, final String resid, final AreaMarker areaMarker) {
        AreaStyle as = cusstyle.get(resid);
        if (as == null) {
            as = defstyle;
        }

        int sc = 0xFF0000;
        int fc = 0xFF0000;
        try {
            sc = Integer.parseInt(as.getStrokecolor().substring(1), 16);
            fc = Integer.parseInt(as.getFillcolor().substring(1), 16);
        } catch (NumberFormatException nfx) {
        }

        areaMarker.setLineStyle(as.getStrokeweight(), as.getStrokeopacity(), sc);
        areaMarker.setFillStyle(as.getFillopacity(), fc);
        areaMarker.setBoostFlag(as.isBoost());
    }

    /**
     * Handle specific claimChunk on specific world
     */
    public static void handleClaimChunkOnWorld(final DynmapClaimChunkPlugin kernel, final String formatInfoWindow, final String playerName,
                                               final String world, final LinkedList<ClaimChunkBlock> blocks,
                                               final Map<String, AreaMarker> newmap) {

        if (blocks.isEmpty())
            return;

        LinkedList<ClaimChunkBlock> nodevals = new LinkedList<>();
        TileFlags curblks = new TileFlags();
        /* Loop through blocks: set flags on blockmaps */
        for (final ClaimChunkBlock b : blocks) {
            curblks.setFlag(b.getX(), b.getZ(), true); /* Set flag for block */
            nodevals.addLast(b);
        }
        /* Loop through until we don't find more areas */
        int poly_index = 0; /* Index of polygon for given claimChunk */
        while (nodevals != null) {
            LinkedList<ClaimChunkBlock> ournodes = null;
            LinkedList<ClaimChunkBlock> newlist = null;
            TileFlags ourblks = null;
            int minx = Integer.MAX_VALUE;
            int minz = Integer.MAX_VALUE;
            for (ClaimChunkBlock node : nodevals) {
                final int nodex = node.getX();
                final int nodez = node.getZ();
                /* If we need to start shape, and this block is not part of one yet */
                if ((ourblks == null) && curblks.getFlag(nodex, nodez)) {
                    ourblks = new TileFlags();  /* Create map for shape */
                    ournodes = new LinkedList<>();
                    floodFillTarget(curblks, ourblks, nodex, nodez);   /* Copy shape */
                    ournodes.add(node); /* Add it to our node list */
                    minx = nodex;
                    minz = nodez;
                }
                /* If shape found, and we're in it, add to our node list */
                else if ((ourblks != null) && ourblks.getFlag(nodex, nodez)) {
                    ournodes.add(node);
                    if (nodex < minx) {
                        minx = nodex;
                        minz = nodez;
                    } else if ((nodex == minx) && (nodez < minz)) {
                        minz = nodez;
                    }
                } else {  /* Else, keep it in the list for the next polygon */
                    if (newlist == null) newlist = new LinkedList<>();
                    newlist.add(node);
                }
            }
            nodevals = newlist; /* Replace list (null if no more to process) */
            if (ourblks != null) {
                /* Trace outline of blocks - start from minx, minz going to x+ */
                int init_x = minx;
                int init_z = minz;
                int cur_x = minx;
                int cur_z = minz;
                Direction dir = Direction.XPLUS;
                ArrayList<int[]> linelist = new ArrayList<>();
                linelist.add(new int[]{init_x, init_z}); // Add start point
                while ((cur_x != init_x) || (cur_z != init_z) || (dir != Direction.ZMINUS)) {
                    switch (dir) {
                        case XPLUS: /* Segment in X+ Direction */
                            if (!ourblks.getFlag(cur_x + 1, cur_z)) { /* Right turn? */
                                linelist.add(new int[]{cur_x + 1, cur_z}); /* Finish line */
                                dir = Direction.ZPLUS;  /* Change Direction */
                            } else if (!ourblks.getFlag(cur_x + 1, cur_z - 1)) {  /* Straight? */
                                cur_x++;
                            } else {  /* Left turn */
                                linelist.add(new int[]{cur_x + 1, cur_z}); /* Finish line */
                                dir = Direction.ZMINUS;
                                cur_x++;
                                cur_z--;
                            }
                            break;
                        case ZPLUS: /* Segment in Z+ Direction */
                            if (!ourblks.getFlag(cur_x, cur_z + 1)) { /* Right turn? */
                                linelist.add(new int[]{cur_x + 1, cur_z + 1}); /* Finish line */
                                dir = Direction.XMINUS;  /* Change Direction */
                            } else if (!ourblks.getFlag(cur_x + 1, cur_z + 1)) {  /* Straight? */
                                cur_z++;
                            } else {  /* Left turn */
                                linelist.add(new int[]{cur_x + 1, cur_z + 1}); /* Finish line */
                                dir = Direction.XPLUS;
                                cur_x++;
                                cur_z++;
                            }
                            break;
                        case XMINUS: /* Segment in X- Direction */
                            if (!ourblks.getFlag(cur_x - 1, cur_z)) { /* Right turn? */
                                linelist.add(new int[]{cur_x, cur_z + 1}); /* Finish line */
                                dir = Direction.ZMINUS;  /* Change Direction */
                            } else if (!ourblks.getFlag(cur_x - 1, cur_z + 1)) {  /* Straight? */
                                cur_x--;
                            } else {  /* Left turn */
                                linelist.add(new int[]{cur_x, cur_z + 1}); /* Finish line */
                                dir = Direction.ZPLUS;
                                cur_x--;
                                cur_z++;
                            }
                            break;
                        case ZMINUS: /* Segment in Z- Direction */
                            if (!ourblks.getFlag(cur_x, cur_z - 1)) { /* Right turn? */
                                linelist.add(new int[]{cur_x, cur_z}); /* Finish line */
                                dir = Direction.XPLUS;  /* Change Direction */
                            } else if (!ourblks.getFlag(cur_x - 1, cur_z - 1)) {  /* Straight? */
                                cur_z--;
                            } else {  /* Left turn */
                                linelist.add(new int[]{cur_x, cur_z}); /* Finish line */
                                dir = Direction.XMINUS;
                                cur_x--;
                                cur_z--;
                            }
                            break;
                    }
                }

                /* Build information for specific area */
                final String polyId = new StringBuilder().append(playerName).append("__").append(world).append("__").append(poly_index).toString();

                final int sz = linelist.size();
                final double[] x = new double[sz];
                final double[] z = new double[sz];
                for (int i = 0; i < sz; i++) {
                    final int[] line = linelist.get(i);
                    x[i] = (double) line[0] * (double) MAX_BLOCK_SIZE;
                    z[i] = (double) line[1] * (double) MAX_BLOCK_SIZE;
                }

                /* Find existing one */
                AreaMarker areaMarker = kernel.getResareas().remove(polyId); /* Existing area? */
                if (areaMarker == null) {
                    areaMarker = kernel.getSet().createAreaMarker(polyId, playerName, false, world, x, z, false);
                    if (areaMarker == null) {
                        DynmapClaimChunkPlugin.info("error adding area marker " + polyId);
                        return;
                    }
                } else {
                    areaMarker.setCornerLocations(x, z); /* Replace corner locations */
                    areaMarker.setLabel(playerName);   /* Update label */
                }
                areaMarker.setDescription(formatInfoWindow); /* Set popup */

                /* Set line and fill properties */
                addStyle(kernel.getCusstyle(), kernel.getDefstyle(), playerName, areaMarker);

                /* Add to map */
                newmap.put(polyId, areaMarker);
                poly_index++;
            }
        }
    }
}
