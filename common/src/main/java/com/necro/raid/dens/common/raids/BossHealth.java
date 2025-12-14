package com.necro.raid.dens.common.raids;

import com.necro.raid.dens.common.network.RaidDenNetworkMessages;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages the Boss HP bar (center of screen).
 * This HP only decreases when a Pokémon is defeated, not during battle.
 * 
 * The boss HP = normalPokemonHp * healthMultiplier
 * Each time a player defeats the Pokémon, boss HP is reduced by normalPokemonHp.
 */
public class BossHealth {
    
    private final ServerBossEvent bossEvent;
    private final List<ServerPlayer> trackedPlayers;
    
    private final float normalPokemonHp;   // HP of the normal Pokémon (e.g., 100)
    private final int healthMultiplier;     // Multiplier for boss HP (e.g., 8)
    private float currentBossHp;            // Current boss HP
    private final float maxBossHp;          // Max boss HP (normalPokemonHp * multiplier)
    
    private String bossName;
    
    /**
     * Create a new BossHealth manager.
     * 
     * @param bossName Name to display on the boss bar
     * @param normalPokemonHp The normal HP of the Pokémon players fight
     * @param healthMultiplier The multiplier to calculate total boss HP
     */
    public BossHealth(String bossName, float normalPokemonHp, int healthMultiplier) {
        this.bossName = bossName;
        this.normalPokemonHp = normalPokemonHp;
        this.healthMultiplier = healthMultiplier;
        this.maxBossHp = normalPokemonHp * healthMultiplier;
        this.currentBossHp = this.maxBossHp;
        
        this.trackedPlayers = new ArrayList<>();
        
        // Create boss bar
        this.bossEvent = new ServerBossEvent(
            Component.literal(bossName)
                .withStyle(ChatFormatting.BOLD)
                .withStyle(ChatFormatting.WHITE),
            BossEvent.BossBarColor.WHITE,
            BossEvent.BossBarOverlay.NOTCHED_10
        );
        this.bossEvent.setProgress(1.0f);
    }
    
    /**
     * Add a player to receive boss bar updates.
     */
    public void addPlayer(ServerPlayer player) {
        if (!this.trackedPlayers.contains(player)) {
            this.trackedPlayers.add(player);
            this.bossEvent.addPlayer(player);
            // Sync current boss HP ratio to new player
            RaidDenNetworkMessages.SYNC_HEALTH.accept(player, getHealthRatio());
        }
    }
    
    /**
     * Remove a player from boss bar updates.
     */
    public void removePlayer(ServerPlayer player) {
        this.trackedPlayers.remove(player);
        this.bossEvent.removePlayer(player);
    }
    
    /**
     * Called when a player defeats the Pokémon in battle.
     * Reduces boss HP by the normal Pokémon's max HP.
     * 
     * @return true if the boss is now defeated (HP <= 0)
     */
    public boolean onPokemonDefeated() {
        return applyDamage(this.normalPokemonHp);
    }
    
    /**
     * Apply custom damage to the boss HP.
     * 
     * @param damage Amount of damage to apply
     * @return true if the boss is now defeated (HP <= 0)
     */
    public boolean applyDamage(float damage) {
        this.currentBossHp = Math.max(0, this.currentBossHp - damage);
        updateBossBar();
        syncToAllPlayers();
        return this.currentBossHp <= 0;
    }
    
    /**
     * Update the boss bar name with time remaining.
     */
    public void updateWithTime(int remainingSeconds) {
        int minutes = remainingSeconds / 60;
        int seconds = remainingSeconds % 60;
        String timeStr = String.format("%d:%02d", minutes, seconds);
        
        MutableComponent name = Component.literal(this.bossName)
            .append(Component.literal(" - " + timeStr).withStyle(ChatFormatting.AQUA))
            .withStyle(ChatFormatting.BOLD);
        
        this.bossEvent.setName(name);
    }
    
    /**
     * Update the boss bar progress.
     */
    private void updateBossBar() {
        this.bossEvent.setProgress(getHealthRatio());
    }
    
    /**
     * Sync boss HP to all tracked players.
     * Note: This syncs only the boss bar, NOT the Pokémon battle HP bar.
     */
    private void syncToAllPlayers() {
        float ratio = getHealthRatio();
        for (ServerPlayer player : this.trackedPlayers) {
            RaidDenNetworkMessages.SYNC_HEALTH.accept(player, ratio);
        }
    }
    
    /**
     * Get the current health ratio (0.0 to 1.0).
     */
    public float getHealthRatio() {
        return this.currentBossHp / this.maxBossHp;
    }
    
    /**
     * Get current boss HP.
     */
    public float getCurrentHp() {
        return this.currentBossHp;
    }
    
    /**
     * Get max boss HP.
     */
    public float getMaxHp() {
        return this.maxBossHp;
    }
    
    /**
     * Get the normal Pokémon HP (damage per defeat).
     */
    public float getNormalPokemonHp() {
        return this.normalPokemonHp;
    }
    
    /**
     * Get the health multiplier.
     */
    public int getHealthMultiplier() {
        return this.healthMultiplier;
    }
    
    /**
     * Check if the boss is defeated.
     */
    public boolean isDefeated() {
        return this.currentBossHp <= 0;
    }
    
    /**
     * Hide and cleanup the boss bar.
     */
    public void cleanup() {
        this.bossEvent.setVisible(false);
        this.bossEvent.removeAllPlayers();
        this.trackedPlayers.clear();
    }
    
    /**
     * Get the underlying ServerBossEvent for advanced customization.
     */
    public ServerBossEvent getBossEvent() {
        return this.bossEvent;
    }
}
