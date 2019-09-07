package org.dynmap.claimchunk;

import com.cjburkey.claimchunk.ClaimChunk;
import com.cjburkey.claimchunk.chunk.ChunkPos;
import com.cjburkey.claimchunk.player.SimplePlayerData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.dynmap.DynmapAPI;
import org.dynmap.claimchunk.area.AreaStyle;
import org.dynmap.claimchunk.commons.Direction;
import org.dynmap.claimchunk.pojo.ClaimChunkBlock;
import org.dynmap.claimchunk.pojo.ClaimChunkBlocks;
import org.dynmap.markers.AreaMarker;
import org.dynmap.markers.Marker;
import org.dynmap.markers.MarkerAPI;
import org.dynmap.markers.MarkerSet;

import java.io.IOException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.dynmap.claimchunk.area.AreaCommon.addStyle;
import static org.dynmap.claimchunk.area.AreaCommon.floodFillTarget;
import static org.dynmap.claimchunk.commons.Constant.*;

public class DynmapClaimChunkPlugin extends JavaPlugin {
    private static Logger log;

    private Map<String, AreaMarker> resareas = new HashMap<>();
    private Map<String, Marker> resmark = new HashMap<>();
    private Map<String, AreaStyle> cusstyle;
    private AreaStyle defstyle;

    private boolean reload = false;
    private boolean stop;
    private int interval;

    private Plugin dynmap;
    private DynmapAPI dynmapAPI;
    private Plugin claimChunk;

    // Status of the plugin.
    private MarkerSet set;
    private MarkerAPI markerAPI;
    private boolean use3d;
    private String infoWindow;

    public static void info(String msg) {
        log.log(Level.INFO, msg);
    }

    public static void severe(String msg) {
        log.log(Level.SEVERE, msg);
    }

    public Plugin getDynmap() {
        return dynmap;
    }

    public void setDynmap(Plugin dynmap) {
        this.dynmap = dynmap;
    }

    public boolean isStop() {
        return stop;
    }

    public int getInterval() {
        return interval;
    }

    @Override
    public void onLoad() {
        log = this.getLogger(); // Load the logger
    }

    @Override
    public void onEnable() {
        info("initializing");
        final PluginManager pm = getServer().getPluginManager();

        // Get Dynmap plugin
        dynmap = pm.getPlugin("dynmap");
        if (dynmap == null) {
            severe("Cannot find dynmap!");
            return;
        }

        // Get Dynmap API
        dynmapAPI = (DynmapAPI) dynmap; /* Get API */

        // Get ClaimChunk
        claimChunk = pm.getPlugin("ClaimChunk");
        if (claimChunk == null) {
            severe("Cannot find ClaimChunk!");
            return;
        }

        // If both enabled, activate
        if (dynmap.isEnabled() && claimChunk.isEnabled()) {
            activate();
        }

        try {
            final MetricsLite metricsLite = new MetricsLite(this);
            metricsLite.start();
        } catch (final IOException iox) {
            severe(iox.getMessage());
        }
    }

    @Override
    public void onDisable() {
        if (set != null) {
            set.deleteMarkerSet();
            set = null;
        }
        resareas.clear();
        stop = true;
    }

    public int scheduleSyncDelayedTask(final Runnable run, final long period) {
        return getServer().getScheduler().scheduleSyncDelayedTask(this, run, period);
    }

    /* Update Claimed Chunk information */
    public void updateClaimedChunk() {
        final Map<String, AreaMarker> newmap = new HashMap<>(); /* Build new map */

        /* Parse into ClaimChunk centric mapping, split by world */
        final Map<String, ClaimChunkBlocks> blocks_by_claimchunk = new HashMap<>();

        final ClaimChunk claimChunkInstance = ClaimChunk.getInstance();
        for (final SimplePlayerData spd : claimChunkInstance.getPlayerHandler().getJoinedPlayers()) {
            final String playerId = spd.player.toString();

            ClaimChunkBlocks claimChunkBlocks = blocks_by_claimchunk.get(playerId); /* Look up ClaimChunk */
            if (claimChunkBlocks == null) {    /* Create ClaimChunk block if first time */
                claimChunkBlocks = new ClaimChunkBlocks();
                blocks_by_claimchunk.put(playerId, claimChunkBlocks);
            }

            final ChunkPos[] claimedChunks = claimChunkInstance.getChunkHandler().getClaimedChunks(spd.player);
            for (int i = 0; i < claimedChunks.length; i++) {
                final ChunkPos pos = claimedChunks[i];
                final String world = pos.getWorld();

                LinkedList<ClaimChunkBlock> blocks = claimChunkBlocks.getBlocks().get(world);
                if (blocks == null) {
                    blocks = new LinkedList<>();
                    claimChunkBlocks.getBlocks().put(world, blocks);
                }

                blocks.add(new ClaimChunkBlock(pos.getX(), pos.getZ()));
            }
        }

        for (final SimplePlayerData spd : claimChunkInstance.getPlayerHandler().getJoinedPlayers()) {
            final String playerId = spd.player.toString();
            final String playerName = claimChunkInstance.getPlayerHandler().getUsername(spd.player);
            final ClaimChunkBlocks claimChunkBlocks = blocks_by_claimchunk.get(playerId); /* Look up ClaimChunk */
            if (claimChunkBlocks == null) continue;


            /* Loop through each world that ClaimChunk has blocks on */
            for (Map.Entry<String, LinkedList<ClaimChunkBlock>> worldBlocks : claimChunkBlocks.getBlocks().entrySet()) {
                /* Build popup */
                final String formatInfoWindow = "";//formatInfoWindow(infoWindow, claimChunk);

                handleClaimChunkOnWorld(formatInfoWindow, playerName, worldBlocks.getKey(), worldBlocks.getValue(), newmap);
            }
            claimChunkBlocks.clear();
        }

        /* Now, review old map - anything left is gone */
        for (final AreaMarker oldm : resareas.values()) {
            oldm.deleteMarker();
        }

        /* And replace with new map */
        resareas = newmap;
    }

    /**
     * Handle specific claimChunk on specific world
     */
    public void handleClaimChunkOnWorld(final String formatInfoWindow, final String playerName, final String world,
                                        final LinkedList<ClaimChunkBlock> blocks, final Map<String, AreaMarker> newmap) {

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
                AreaMarker areaMarker = resareas.remove(polyId); /* Existing area? */
                if (areaMarker == null) {
                    areaMarker = set.createAreaMarker(polyId, playerName, false, world, x, z, false);
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
                addStyle(cusstyle, defstyle, playerName, areaMarker);

                /* Add to map */
                newmap.put(polyId, areaMarker);
                poly_index++;
            }
        }
    }

    public void activate() {
        markerAPI = dynmapAPI.getMarkerAPI();
        if (markerAPI == null) {
            severe("Error loading dynmap marker API!");
            return;
        }

        /* Load configuration */
        if (reload) {
            this.reloadConfig();
            if (set != null) {
                set.deleteMarkerSet();
                set = null;
            }
        } else {
            reload = true;
        }

        final FileConfiguration cfg = getConfig();
        cfg.options().copyDefaults(true);   /* Load defaults, if needed */
        this.saveConfig();  /* Save updates, if needed */

        /* Now, add marker set for mobs (make it transient) */
        set = markerAPI.getMarkerSet("claimchunk.markerset");
        if (set == null) {
            set = markerAPI.createMarkerSet("claimchunk.markerset", cfg.getString("layer.name", "ClaimChunk"), null, false);
        } else {
            set.setMarkerSetLabel(cfg.getString("layer.name", "ClaimChunk"));
        }

        if (set == null) {
            severe("Error creating marker set");
            return;
        }
        /* Make sure these are empty (on reload) */
        resareas.clear();
        resmark.clear();

        final int minZoom = cfg.getInt("layer.minzoom", 0);
        if (minZoom > 0) {
            set.setMinZoom(minZoom);
        }

        set.setLayerPriority(cfg.getInt("layer.layerprio", 10));
        use3d = cfg.getBoolean("use3dregions", false);
        infoWindow = cfg.getString("infoWindow", DEF_INFO_WINDOW);

        /* Get style information */
        defstyle = new AreaStyle(markerAPI, cfg, "regionstyle");
        cusstyle = new HashMap<>();

        final ConfigurationSection sect = cfg.getConfigurationSection("custstyle");
        if (sect != null) {
            Set<String> ids = sect.getKeys(false);

            for (final String id : ids) {
                cusstyle.put(id, new AreaStyle(markerAPI, cfg, "custstyle." + id, defstyle));
            }
        }

        /* Set up update job - based on periond */
        int per = cfg.getInt("update.period", 15);
        if (per < 15) {
            per = 15;
        }
        interval = (per * TICKRATE_RATIO);
        stop = false;

        scheduleSyncDelayedTask(new ClaimChunkUpdate(this), interval);
        info("ClaimChunkUpdate - " + (interval / TICKRATE_RATIO) + "s");
        info("version " + this.getDescription().getVersion() + " is activated");
    }
}
