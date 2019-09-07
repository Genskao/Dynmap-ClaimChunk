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
import org.dynmap.claimchunk.area.AreaCommon;
import org.dynmap.claimchunk.area.AreaStyle;
import org.dynmap.claimchunk.pojo.ClaimChunkBlock;
import org.dynmap.claimchunk.pojo.ClaimChunkBlocks;
import org.dynmap.markers.AreaMarker;
import org.dynmap.markers.Marker;
import org.dynmap.markers.MarkerAPI;
import org.dynmap.markers.MarkerSet;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.dynmap.claimchunk.commons.Constant.DEF_INFO_WINDOW;

public class DynmapClaimChunkPlugin extends JavaPlugin {
    private static Logger log;

    private Map<String, AreaMarker> resareas = new HashMap<>();
    private Map<String, Marker> resmark = new HashMap<>();
    private Map<String, AreaStyle> cusstyle;
    private AreaStyle defstyle;

    private Plugin dynmap;
    private DynmapAPI dynmapAPI;
    private Plugin claimChunk;

    // Status of the plugin.
    private boolean stop;
    private MarkerSet set;
    private MarkerAPI markerAPI;
    private long updperiod;
    private boolean reload = false;
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
        return this.stop;
    }

    public long getUpdperiod() {
        return updperiod;
    }

    public MarkerAPI getMarkerAPI() {
        return markerAPI;
    }

    public MarkerSet getSet() {
        return set;
    }

    public Map<String, AreaMarker> getResareas() {
        return resareas;
    }

    public AreaStyle getDefstyle() {
        return defstyle;
    }

    public Map<String, AreaStyle> getCusstyle() {
        return cusstyle;
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

                AreaCommon.handleClaimChunkOnWorld(this, formatInfoWindow, playerName, worldBlocks.getKey(), worldBlocks.getValue(), newmap);
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
        updperiod = (per * 20);
        stop = false;

        scheduleSyncDelayedTask(new ClaimChunkUpdate(this), updperiod);
        info("ClaimChunkUpdate - " + (updperiod / 20) + "s");

        info("version " + this.getDescription().getVersion() + " is activated");
    }
}
