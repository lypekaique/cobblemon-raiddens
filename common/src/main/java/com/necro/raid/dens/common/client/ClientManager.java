package com.necro.raid.dens.common.client;

import com.cobblemon.mod.common.client.CobblemonClient;
import com.necro.raid.dens.common.CobblemonRaidDens;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;

public class ClientManager {
    public static final List<Callable<Boolean>> RAID_INSTRUCTION_QUEUE = new ArrayList<>();
    
    // Boss HP ratio (0.0 to 1.0) - used for the center boss bar only
    // This is INDEPENDENT from the Pokemon battle HP bar (right side Cobblemon UI)
    private static float bossHealthRatio = 1.0f;
    
    /**
     * Sync the boss health ratio from server.
     * This only affects the boss bar display, NOT the Pokemon battle HP.
     * 
     * @param ratio Health ratio from 0.0 to 1.0
     */
    public static void syncBossHealthRatio(float ratio) {
        bossHealthRatio = ratio;
    }
    
    /**
     * Get the current boss health ratio.
     */
    public static float getBossHealthRatio() {
        return bossHealthRatio;
    }
    
    /**
     * Reset boss health ratio (when raid ends).
     */
    public static void resetBossHealth() {
        bossHealthRatio = 1.0f;
    }

    public static void clientTick() {
        if (RAID_INSTRUCTION_QUEUE.isEmpty()) return;
        else if (CobblemonClient.INSTANCE.getBattle() == null) return;
        Iterator<Callable<Boolean>> iter = RAID_INSTRUCTION_QUEUE.iterator();
        try {
            while (iter.hasNext()) {
                if (iter.next().call()) iter.remove();
            }
        }
        catch (Exception e) {
            CobblemonRaidDens.LOGGER.info("Error during client serverTick: {}", e.toString());
        }
    }
}
