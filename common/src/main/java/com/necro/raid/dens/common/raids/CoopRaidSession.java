package com.necro.raid.dens.common.raids;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.necro.raid.dens.common.CobblemonRaidDens;
import com.necro.raid.dens.common.events.RaidEndEvent;
import com.necro.raid.dens.common.events.RaidEvents;
import com.necro.raid.dens.common.network.RaidDenNetworkMessages;
import com.necro.raid.dens.common.util.IRaidAccessor;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;

import java.util.*;

/**
 * Cooperative Raid Session
 * 
 * This class manages a cooperative raid where:
 * - Boss HP = Normal Pokémon HP × Multiplier (e.g., 100 × 8 = 800)
 * - Players fight "normal" versions of the Pokémon (with normal HP)
 * - Each time a player defeats the normal Pokémon, boss HP is reduced by the Pokémon's max HP
 * - No failure limits - unlimited Pokémon usage and healing allowed
 * - 10-minute time limit
 * - Shared boss HP across all players
 * - Win = reduce boss HP to 0 before time runs out
 */
public class CoopRaidSession {
    
    // Default raid duration in ticks (10 minutes = 600 seconds = 12000 ticks)
    public static final int DEFAULT_RAID_DURATION_TICKS = 12000;
    
    // Active coop raids mapped by raid ID
    public static final Map<UUID, CoopRaidSession> ACTIVE_COOP_RAIDS = new HashMap<>();
    
    private final UUID raidId;
    private final PokemonEntity bossEntity;
    private final RaidBoss raidBoss;
    private final ServerBossEvent bossEvent;
    
    // Participating players
    private final List<ServerPlayer> participants;
    private final Map<UUID, Integer> playerDefeats; // Track how many times each player defeated the normal Pokémon
    
    // Boss HP system
    private final int normalPokemonHp;     // HP of the normal Pokémon players fight (e.g., 100)
    private final int healthMultiplier;     // Multiplier for boss HP (e.g., 8)
    private int currentBossHp;              // Current boss HP (starts at normalPokemonHp * healthMultiplier)
    private final int maxBossHp;            // Max boss HP
    
    // Timer system
    private int remainingTicks;             // Remaining time in ticks
    private final int totalDurationTicks;   // Total raid duration in ticks
    private boolean raidActive;             // Is the raid currently active
    private boolean raidEnded;              // Has the raid ended (to prevent double processing)
    
    /**
     * Create a new coop raid session with explicit multiplier and duration
     */
    public CoopRaidSession(PokemonEntity bossEntity, RaidBoss raidBoss, int healthMultiplier, int durationTicks) {
        this.raidId = UUID.randomUUID();
        this.bossEntity = bossEntity;
        this.raidBoss = raidBoss != null ? raidBoss : ((IRaidAccessor) bossEntity).getRaidBoss();
        
        // Setup boss bar
        this.bossEvent = new ServerBossEvent(
            ((MutableComponent) bossEntity.getName())
                .append(Component.literal(" - RAID"))
                .withStyle(ChatFormatting.BOLD)
                .withStyle(ChatFormatting.RED),
            BossEvent.BossBarColor.RED,
            BossEvent.BossBarOverlay.NOTCHED_10
        );
        
        this.participants = new ArrayList<>();
        this.playerDefeats = new HashMap<>();
        
        // Calculate HP values
        // The normal Pokémon HP is the base HP before any multiplier
        Pokemon pokemon = bossEntity.getPokemon();
        this.normalPokemonHp = pokemon.getMaxHealth() / healthMultiplier; // Get original HP
        this.healthMultiplier = healthMultiplier;
        this.maxBossHp = this.normalPokemonHp * healthMultiplier;
        this.currentBossHp = this.maxBossHp;
        
        // Timer setup
        this.totalDurationTicks = durationTicks;
        this.remainingTicks = durationTicks;
        this.raidActive = true;
        this.raidEnded = false;
        
        // Register this raid
        ((IRaidAccessor) bossEntity).setCoopRaidSession(this);
        ACTIVE_COOP_RAIDS.put(this.raidId, this);
    }
    
    /**
     * Create a coop raid session using tier-based multiplier from config
     */
    public CoopRaidSession(PokemonEntity bossEntity, RaidBoss raidBoss) {
        this(bossEntity, raidBoss,
            CobblemonRaidDens.TIER_CONFIG.get(raidBoss.getTier()).coopHealthMultiplier(),
            CobblemonRaidDens.CONFIG.coop_raid_duration_seconds * 20);
    }
    
    /**
     * Legacy constructor for manual coop raids without RaidBoss
     */
    public CoopRaidSession(PokemonEntity bossEntity, int healthMultiplier, int durationTicks) {
        this(bossEntity, null, healthMultiplier, durationTicks);
    }
    
    public CoopRaidSession(PokemonEntity bossEntity, int healthMultiplier) {
        this(bossEntity, null, healthMultiplier, DEFAULT_RAID_DURATION_TICKS);
    }
    
    /**
     * Add a player to this coop raid session
     */
    public void addPlayer(ServerPlayer player) {
        if (!this.participants.contains(player)) {
            this.participants.add(player);
            this.playerDefeats.put(player.getUUID(), 0);
            this.bossEvent.addPlayer(player);
            
            // Notify player
            player.sendSystemMessage(Component.translatable("message.cobblemonraiddens.coop_raid.joined")
                .withStyle(ChatFormatting.GREEN));
            
            // Send current status
            sendStatusToPlayer(player);
        }
    }
    
    /**
     * Remove a player from the coop raid session
     */
    public void removePlayer(ServerPlayer player) {
        this.participants.remove(player);
        this.bossEvent.removePlayer(player);
    }
    
    /**
     * Called when a player defeats their normal Pokémon instance
     * Reduces boss HP by the normal Pokémon's max HP
     */
    public void onPlayerVictory(ServerPlayer player) {
        if (!this.raidActive || this.raidEnded) return;
        
        // Increment player's defeat count
        int defeats = this.playerDefeats.getOrDefault(player.getUUID(), 0) + 1;
        this.playerDefeats.put(player.getUUID(), defeats);
        
        // Reduce boss HP
        int damageDealt = this.normalPokemonHp;
        this.currentBossHp = Math.max(0, this.currentBossHp - damageDealt);
        
        // Update boss bar
        float progress = (float) this.currentBossHp / this.maxBossHp;
        this.bossEvent.setProgress(progress);
        
        // Sync health to all participants
        this.participants.forEach(p -> RaidDenNetworkMessages.SYNC_HEALTH.accept(p, progress));
        
        // Broadcast damage message
        Component message = Component.translatable("message.cobblemonraiddens.coop_raid.damage_dealt",
                player.getName(),
                damageDealt,
                this.currentBossHp,
                this.maxBossHp)
            .withStyle(ChatFormatting.YELLOW);
        broadcastMessage(message);
        
        // Check for victory
        if (this.currentBossHp <= 0) {
            endRaid(true);
        }
    }
    
    /**
     * Called every server tick to update the raid timer
     */
    public void tick() {
        if (!this.raidActive || this.raidEnded) return;
        
        this.remainingTicks--;
        
        // Update boss bar name with time remaining
        if (this.remainingTicks % 20 == 0) { // Update every second
            updateBossBarWithTime();
        }
        
        // Broadcast time warnings at key intervals
        int remainingSeconds = this.remainingTicks / 20;
        if (this.remainingTicks % 20 == 0) {
            if (remainingSeconds == 300 || remainingSeconds == 180 || 
                remainingSeconds == 60 || remainingSeconds == 30 || 
                remainingSeconds == 10 || remainingSeconds <= 5 && remainingSeconds > 0) {
                broadcastTimeWarning(remainingSeconds);
            }
        }
        
        // Check for timeout
        if (this.remainingTicks <= 0) {
            endRaid(false);
        }
    }
    
    /**
     * Update the boss bar to show remaining time
     */
    private void updateBossBarWithTime() {
        int remainingSeconds = this.remainingTicks / 20;
        int minutes = remainingSeconds / 60;
        int seconds = remainingSeconds % 60;
        
        String timeStr = String.format("%d:%02d", minutes, seconds);
        
        MutableComponent name = ((MutableComponent) this.bossEntity.getName())
            .append(Component.literal(" - "))
            .append(Component.literal(timeStr).withStyle(ChatFormatting.AQUA))
            .append(Component.literal(" - HP: " + this.currentBossHp + "/" + this.maxBossHp))
            .withStyle(ChatFormatting.BOLD);
        
        this.bossEvent.setName(name);
    }
    
    /**
     * Broadcast a time warning to all participants
     */
    private void broadcastTimeWarning(int remainingSeconds) {
        Component message;
        if (remainingSeconds >= 60) {
            int minutes = remainingSeconds / 60;
            message = Component.translatable("message.cobblemonraiddens.coop_raid.time_warning_minutes", minutes)
                .withStyle(ChatFormatting.GOLD);
        } else {
            message = Component.translatable("message.cobblemonraiddens.coop_raid.time_warning_seconds", remainingSeconds)
                .withStyle(remainingSeconds <= 10 ? ChatFormatting.RED : ChatFormatting.GOLD);
        }
        broadcastMessage(message);
    }
    
    /**
     * End the raid (either victory or timeout)
     */
    public void endRaid(boolean victory) {
        if (this.raidEnded) return;
        this.raidEnded = true;
        this.raidActive = false;
        
        // Remove boss bar
        this.bossEvent.setVisible(false);
        this.bossEvent.removeAllPlayers();
        
        if (victory) {
            handleVictory();
        } else {
            handleDefeat();
        }
        
        // Kill boss entity if victory
        if (victory && !this.bossEntity.isDeadOrDying()) {
            this.bossEntity.setHealth(0f);
        }
        
        // Cleanup
        ACTIVE_COOP_RAIDS.remove(this.raidId);
        ((IRaidAccessor) this.bossEntity).setCoopRaidSession(null);
    }
    
    /**
     * Handle raid victory
     */
    private void handleVictory() {
        // Calculate time taken
        int timeTakenTicks = this.totalDurationTicks - this.remainingTicks;
        int timeTakenSeconds = timeTakenTicks / 20;
        int minutes = timeTakenSeconds / 60;
        int seconds = timeTakenSeconds % 60;
        
        // Victory message
        Component victoryMessage = Component.translatable("message.cobblemonraiddens.coop_raid.victory",
                minutes, seconds)
            .withStyle(ChatFormatting.GREEN)
            .withStyle(ChatFormatting.BOLD);
        broadcastMessage(victoryMessage);
        
        // Show player contributions
        broadcastMessage(Component.translatable("message.cobblemonraiddens.coop_raid.contributions")
            .withStyle(ChatFormatting.GOLD));
        
        for (Map.Entry<UUID, Integer> entry : this.playerDefeats.entrySet()) {
            ServerPlayer player = getPlayerByUUID(entry.getKey());
            if (player != null) {
                int defeats = entry.getValue();
                int damageContribution = defeats * this.normalPokemonHp;
                Component contribution = Component.literal("  - ")
                    .append(player.getName())
                    .append(Component.literal(": " + defeats + " defeats (" + damageContribution + " damage)"))
                    .withStyle(ChatFormatting.YELLOW);
                broadcastMessage(contribution);
            }
        }
        
        // Give rewards to all participants
        Pokemon cachedReward = null;
        if (CobblemonRaidDens.CONFIG.sync_rewards) {
            cachedReward = this.raidBoss.getRewardPokemon(null);
            cachedReward.setShiny(this.bossEntity.getPokemon().getShiny());
            cachedReward.setAbility$common(this.bossEntity.getPokemon().getAbility());
            cachedReward.setGender(this.bossEntity.getPokemon().getGender());
            cachedReward.setNature(this.bossEntity.getPokemon().getNature());
        }
        
        for (ServerPlayer player : this.participants) {
            new RewardHandler(this.raidBoss, player, true, cachedReward).sendRewardMessage();
            RaidEvents.RAID_END.emit(new RaidEndEvent(player, this.raidBoss, this.bossEntity.getPokemon(), true));
        }
    }
    
    /**
     * Handle raid defeat (timeout)
     */
    private void handleDefeat() {
        // Defeat message
        Component defeatMessage = Component.translatable("message.cobblemonraiddens.coop_raid.defeat",
                this.currentBossHp, this.maxBossHp)
            .withStyle(ChatFormatting.RED)
            .withStyle(ChatFormatting.BOLD);
        broadcastMessage(defeatMessage);
        
        // Emit raid end event for each participant
        for (ServerPlayer player : this.participants) {
            player.sendSystemMessage(Component.translatable("message.cobblemonraiddens.raid.raid_fail"));
            RaidEvents.RAID_END.emit(new RaidEndEvent(player, this.raidBoss, this.bossEntity.getPokemon(), false));
        }
    }
    
    /**
     * Send current raid status to a specific player
     */
    private void sendStatusToPlayer(ServerPlayer player) {
        int remainingSeconds = this.remainingTicks / 20;
        int minutes = remainingSeconds / 60;
        int seconds = remainingSeconds % 60;
        
        Component status = Component.translatable("message.cobblemonraiddens.coop_raid.status",
                this.currentBossHp,
                this.maxBossHp,
                String.format("%d:%02d", minutes, seconds),
                this.participants.size())
            .withStyle(ChatFormatting.AQUA);
        player.sendSystemMessage(status);
    }
    
    /**
     * Broadcast a message to all participants
     */
    private void broadcastMessage(Component message) {
        for (ServerPlayer player : this.participants) {
            player.sendSystemMessage(message);
        }
    }
    
    /**
     * Get a player by UUID from the participants list
     */
    private ServerPlayer getPlayerByUUID(UUID uuid) {
        for (ServerPlayer player : this.participants) {
            if (player.getUUID().equals(uuid)) {
                return player;
            }
        }
        return null;
    }
    
    // Getters
    
    public UUID getRaidId() {
        return this.raidId;
    }
    
    public PokemonEntity getBossEntity() {
        return this.bossEntity;
    }
    
    public RaidBoss getRaidBoss() {
        return this.raidBoss;
    }
    
    public List<ServerPlayer> getParticipants() {
        return this.participants;
    }
    
    public int getCurrentBossHp() {
        return this.currentBossHp;
    }
    
    public int getMaxBossHp() {
        return this.maxBossHp;
    }
    
    public int getNormalPokemonHp() {
        return this.normalPokemonHp;
    }
    
    public int getHealthMultiplier() {
        return this.healthMultiplier;
    }
    
    public int getRemainingTicks() {
        return this.remainingTicks;
    }
    
    public int getRemainingSeconds() {
        return this.remainingTicks / 20;
    }
    
    public boolean isRaidActive() {
        return this.raidActive;
    }
    
    public boolean hasEnded() {
        return this.raidEnded;
    }
    
    /**
     * Static tick method to update all active coop raids
     */
    public static void tickAllRaids() {
        List<CoopRaidSession> raids = new ArrayList<>(ACTIVE_COOP_RAIDS.values());
        for (CoopRaidSession raid : raids) {
            raid.tick();
        }
    }
    
    /**
     * Get a coop raid session by raid ID
     */
    public static CoopRaidSession getByRaidId(UUID raidId) {
        return ACTIVE_COOP_RAIDS.get(raidId);
    }
    
    /**
     * Get a coop raid session by boss entity
     */
    public static CoopRaidSession getByBossEntity(PokemonEntity entity) {
        return ((IRaidAccessor) entity).getCoopRaidSession();
    }
    
    /**
     * Check if a player is currently in a coop raid
     */
    public static boolean isPlayerInCoopRaid(ServerPlayer player) {
        for (CoopRaidSession raid : ACTIVE_COOP_RAIDS.values()) {
            if (raid.getParticipants().contains(player)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Get the coop raid session a player is currently in
     */
    public static CoopRaidSession getPlayerCoopRaid(ServerPlayer player) {
        for (CoopRaidSession raid : ACTIVE_COOP_RAIDS.values()) {
            if (raid.getParticipants().contains(player)) {
                return raid;
            }
        }
        return null;
    }
}
