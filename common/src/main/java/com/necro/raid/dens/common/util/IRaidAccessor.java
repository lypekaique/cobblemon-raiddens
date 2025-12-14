package com.necro.raid.dens.common.util;

import com.necro.raid.dens.common.raids.CoopRaidSession;
import com.necro.raid.dens.common.raids.RaidBoss;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

public interface IRaidAccessor {
    UUID getRaidId();

    void setRaidId(UUID raidId);

    RaidBoss getRaidBoss();

    void setRaidBoss(ResourceLocation raidBoss);

    boolean isRaidBoss();

    CoopRaidSession getCoopRaidSession();

    void setCoopRaidSession(CoopRaidSession session);

    boolean isInCoopRaid();
    
    /**
     * Check if boss is allowed to die (only when global raid HP reaches 0)
     */
    boolean isAllowedToDie();
    
    /**
     * Set whether boss is allowed to die
     */
    void setAllowedToDie(boolean allowed);
}
