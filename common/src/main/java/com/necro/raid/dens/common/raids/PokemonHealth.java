package com.necro.raid.dens.common.raids;

import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Manages individual Pokémon battle HP for each player.
 * This is the HP shown on the right side of the screen (Cobblemon battle UI).
 * 
 * Each player fights a "normal" Pokémon with normal HP.
 * The HP decreases normally during battle as the player attacks.
 * When the Pokémon is defeated, the player wins and BossHealth is reduced.
 * 
 * This class does NOT sync with the boss HP bar - they are independent.
 */
public class PokemonHealth {
    
    // Track battle HP per player (what HP the Pokemon has in their individual battle)
    private final Map<UUID, Float> playerBattleHp;
    
    // The normal/base HP of the Pokémon (without boss multiplier)
    private final float normalMaxHp;
    
    /**
     * Create a new PokemonHealth manager.
     * 
     * @param normalMaxHp The normal max HP of the Pokémon for battles
     */
    public PokemonHealth(float normalMaxHp) {
        this.normalMaxHp = normalMaxHp;
        this.playerBattleHp = new HashMap<>();
    }
    
    /**
     * Initialize battle HP for a player starting a new battle.
     * The Pokémon starts at full HP.
     */
    public void initPlayerBattle(ServerPlayer player) {
        this.playerBattleHp.put(player.getUUID(), this.normalMaxHp);
    }
    
    /**
     * Update the tracked battle HP for a player.
     * Called when damage is dealt during battle.
     * 
     * @param player The player in battle
     * @param currentHp The current HP of the Pokémon in their battle
     */
    public void updateBattleHp(ServerPlayer player, float currentHp) {
        this.playerBattleHp.put(player.getUUID(), currentHp);
    }
    
    /**
     * Get the current battle HP for a player's battle.
     * 
     * @param player The player
     * @return Current HP, or normalMaxHp if not tracking
     */
    public float getBattleHp(ServerPlayer player) {
        return this.playerBattleHp.getOrDefault(player.getUUID(), this.normalMaxHp);
    }
    
    /**
     * Calculate damage dealt by a player in their current battle.
     * 
     * @param player The player
     * @return Damage dealt from max HP to current tracked HP
     */
    public float getDamageDealt(ServerPlayer player) {
        float currentHp = getBattleHp(player);
        return this.normalMaxHp - currentHp;
    }
    
    /**
     * Reset a player's battle tracking (when battle ends or player leaves).
     */
    public void resetPlayerBattle(ServerPlayer player) {
        this.playerBattleHp.put(player.getUUID(), this.normalMaxHp);
    }
    
    /**
     * Remove a player from tracking entirely.
     */
    public void removePlayer(ServerPlayer player) {
        this.playerBattleHp.remove(player.getUUID());
    }
    
    /**
     * Check if a player's Pokémon battle is at 0 HP (defeated).
     */
    public boolean isPokemonDefeated(ServerPlayer player) {
        return getBattleHp(player) <= 0;
    }
    
    /**
     * Get the normal max HP value.
     */
    public float getNormalMaxHp() {
        return this.normalMaxHp;
    }
    
    /**
     * Get the health ratio for a player's battle (0.0 to 1.0).
     */
    public float getHealthRatio(ServerPlayer player) {
        return getBattleHp(player) / this.normalMaxHp;
    }
    
    /**
     * Clear all player tracking data.
     */
    public void cleanup() {
        this.playerBattleHp.clear();
    }
}
