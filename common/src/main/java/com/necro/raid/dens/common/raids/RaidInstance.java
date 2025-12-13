package com.necro.raid.dens.common.raids;

import com.cobblemon.mod.common.CobblemonSounds;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.api.pokemon.stats.Stat;
import com.cobblemon.mod.common.api.pokemon.stats.Stats;
import com.cobblemon.mod.common.battles.ActiveBattlePokemon;
import com.cobblemon.mod.common.battles.BagItemActionResponse;
import com.cobblemon.mod.common.battles.PassActionResponse;
import com.cobblemon.mod.common.battles.ShowdownActionResponse;
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.item.battle.BagItem;
import com.cobblemon.mod.common.net.messages.client.battle.BattleApplyPassResponsePacket;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.necro.raid.dens.common.CobblemonRaidDens;
import com.necro.raid.dens.common.config.TierConfig;
import com.necro.raid.dens.common.events.RaidEndEvent;
import com.necro.raid.dens.common.events.RaidEvents;
import com.necro.raid.dens.common.network.RaidDenNetworkMessages;
import com.necro.raid.dens.common.showdown.RaidBagItems;
import com.necro.raid.dens.common.showdown.bagitems.CheerBagItem;
import com.necro.raid.dens.common.showdown.bagitems.PlayerJoinBagItem;
import com.necro.raid.dens.common.showdown.bagitems.StatChangeBagItem;
import com.necro.raid.dens.common.util.IRaidAccessor;
import com.necro.raid.dens.common.util.IRaidBattle;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.function.BiConsumer;

public class RaidInstance {
    private static final Map<String, BiConsumer<RaidInstance, PokemonBattle>> INSTRUCTION_MAP = new HashMap<>();

    private final PokemonEntity bossEntity;
    private final RaidBoss raidBoss;
    private final ServerBossEvent bossEvent;
    private final List<PokemonBattle> battles;
    private final Map<UUID, Float> damageCache;
    private final List<ServerPlayer> activePlayers;
    private final List<UUID> failedPlayers;

    private float currentHealth;
    private float maxHealth;
    private final float initMaxHealth;
    private final Map<Integer, String> scriptByTurn;
    private final NavigableMap<Double, String> scriptByHp;

    private final Map<UUID, Integer> cheersLeft;
    private final List<DelayedRunnable> runQueue;
    
    // Raid timer fields
    private int raidTimerTicks;
    private final int raidMaxTicks;

    public RaidInstance(PokemonEntity entity) {
        this.bossEntity = entity;
        this.raidBoss = ((IRaidAccessor) entity).getRaidBoss();
        this.bossEvent = new ServerBossEvent(
            ((MutableComponent) entity.getName()).withStyle(ChatFormatting.BOLD).withStyle(ChatFormatting.WHITE),
            BossEvent.BossBarColor.WHITE, BossEvent.BossBarOverlay.NOTCHED_10
        );

        this.battles = new ArrayList<>();
        this.damageCache = new HashMap<>();

        this.activePlayers = new ArrayList<>();
        this.failedPlayers = new ArrayList<>();

        this.currentHealth = entity.getPokemon().getCurrentHealth();
        this.maxHealth = entity.getPokemon().getMaxHealth();
        this.initMaxHealth = this.maxHealth;

        this.scriptByTurn = new HashMap<>();
        this.scriptByHp = new TreeMap<>();
        raidBoss.getScript().forEach((key, func) -> {
            if (!INSTRUCTION_MAP.containsKey(func)) return;
            try {
                if (key.startsWith("turn:")) {
                    this.scriptByTurn.put(Integer.parseInt(key.split(":")[1]), func);
                }
                else if (key.startsWith("hp:")) {
                    double threshold = Double.parseDouble(key.split(":")[1]);
                    if ((this.currentHealth / this.maxHealth) < threshold) return;
                    this.scriptByHp.put(threshold, func);
                }
            }
            catch (Exception ignored) {}
        });

        this.cheersLeft = new HashMap<>();
        this.runQueue = new ArrayList<>();
        this.runQueue.add(new DelayedRunnable(() -> {
            if (this.bossEntity.isDeadOrDying()) return;
            for (ServerPlayer player : this.activePlayers) {
                if (player.level() != this.bossEntity.level()) this.removePlayer(player);
            }
        }, 20, true));
        
        // Initialize raid timer
        this.raidMaxTicks = CobblemonRaidDens.CONFIG.coop_raid_duration_seconds * 20;
        this.raidTimerTicks = this.raidMaxTicks;
        
        // Apply tier-based health multiplier to SHARED raid HP (not the Pokemon's actual HP)
        // The Pokemon keeps its normal HP for battles, but the shared raid HP pool is multiplied
        if (this.raidBoss != null) {
            TierConfig tierConfig = CobblemonRaidDens.TIER_CONFIG.get(this.raidBoss.getTier());
            int healthMultiplier = tierConfig.coopHealthMultiplier();
            this.maxHealth = this.initMaxHealth * healthMultiplier;
            this.currentHealth = this.maxHealth;
            // DON'T modify the Pokemon's actual HP - keep it normal for battles
        }
    }

    public void addPlayer(ServerPlayer player, PokemonBattle battle) {
        TierConfig tierConfig = CobblemonRaidDens.TIER_CONFIG.get(this.raidBoss.getTier());
        ((IRaidBattle) battle).setRaidBattle(this);
        this.battles.add(battle);
        this.bossEvent.addPlayer(player);

        // Track the boss's normal HP at battle start (initMaxHealth = normal Pokemon HP)
        this.damageCache.put(player.getUUID(), this.initMaxHealth);
        if (!this.activePlayers.isEmpty() && tierConfig.multiplayerHealthMultiplier() > 1.0f) this.applyHealthMulti(player);
        if (this.scriptByTurn.containsKey(0)) ((IRaidBattle) battle).addToQueue(INSTRUCTION_MAP.get(this.scriptByTurn.get(0)));

        this.cheersLeft.put(player.getUUID(), tierConfig.maxCheers());
        this.activePlayers.add(player);
        RaidDenNetworkMessages.SYNC_HEALTH.accept(player, this.currentHealth / this.maxHealth);
    }

    private void applyHealthMulti(ServerPlayer newPlayer) {
        float bonusHealth = this.initMaxHealth * (CobblemonRaidDens.TIER_CONFIG.get(this.raidBoss.getTier()).multiplayerHealthMultiplier() - 1f) * this.activePlayers.size();
        float currentRatio = this.currentHealth / this.maxHealth;
        this.maxHealth = this.initMaxHealth + bonusHealth;
        this.currentHealth = this.maxHealth * currentRatio;

        // DON'T modify the Pokemon's HP - keep it normal for battles
        // Only the shared raid HP pool (maxHealth/currentHealth) is multiplied

        this.battles.forEach(b -> {
            ServerPlayer player = b.getPlayers().getFirst();
            ((IRaidBattle) b).addToQueue((raid, battle) -> raid.playerJoin(battle, player, newPlayer));
        });
    }

    public void addPlayer(PokemonBattle battle) {
        this.addPlayer(battle.getPlayers().getFirst(), battle);
    }

    public void removePlayer(ServerPlayer player, PokemonBattle battle) {
        this.battles.remove(battle);
        ((IRaidBattle) battle).setRaidBattle(null);
        this.bossEvent.removePlayer(player);
        this.damageCache.remove(player.getUUID());
        // Don't mark as failed so player can rejoin and continue fighting
    }

    public void removePlayer(PokemonBattle battle) {
        this.removePlayer(battle.getPlayers().getFirst(), battle);
    }

    public void removePlayer(ServerPlayer player) {
        this.bossEvent.removePlayer(player);
        this.damageCache.remove(player.getUUID());
    }

    public void syncHealth(ServerPlayer player, PokemonBattle battle, float remainingHealth) {
        if (!this.activePlayers.contains(player) && ((IRaidBattle) battle).isRaidBattle()) this.addPlayer(player, battle);

        // Calculate damage dealt this tick (difference from last known HP)
        float lastKnownHp = this.damageCache.getOrDefault(player.getUUID(), this.initMaxHealth);
        float damage = lastKnownHp - remainingHealth;
        this.damageCache.put(player.getUUID(), remainingHealth);

        // Apply damage to shared raid HP pool
        if (damage > 0) {
            this.currentHealth = Math.clamp(this.currentHealth - damage, 0f, this.maxHealth);
            this.activePlayers.forEach(p -> RaidDenNetworkMessages.SYNC_HEALTH.accept(p, this.currentHealth / this.maxHealth));
        }

        if (this.currentHealth == 0f) {
            this.bossEvent.setProgress(this.currentHealth / this.maxHealth);
            this.queueStopRaid();
        }
        else {
            this.runQueue.add(new DelayedRunnable(() -> {
                this.bossEvent.setProgress(this.currentHealth / this.maxHealth);
                this.runScriptByHp((double) this.currentHealth / this.maxHealth);
            }, 20));
        }
    }
    
    /**
     * Called when a player wins their individual battle against the boss.
     * Resets the player's damage cache so they can start a new battle.
     * Each battle has its own BattlePokemon HP context, so we don't touch the entity HP.
     */
    public void onPlayerBattleWin(ServerPlayer player) {
        // Reset damage cache for this player (they start fresh if they fight again)
        this.damageCache.put(player.getUUID(), this.initMaxHealth);
        // Don't reset boss entity HP - each battle has independent BattlePokemon HP
    }

    public List<ServerPlayer> getPlayers() {
        return this.activePlayers;
    }

    public float getRemainingHealth() {
        return this.currentHealth;
    }
    
    public float getInitMaxHealth() {
        return this.initMaxHealth;
    }
    
    /**
     * Track current battle HP for a player (used for flee/loss damage calculation)
     * Does NOT affect global HP - only tracks locally
     */
    public void trackBattleHp(ServerPlayer player, float currentHp) {
        this.damageCache.put(player.getUUID(), currentHp);
    }
    
    /**
     * Apply damage to global raid HP when battle ends
     * @param player The player who finished the battle
     * @param damage The amount of damage to apply to global HP
     */
    public void applyBattleDamage(ServerPlayer player, float damage) {
        this.currentHealth = Math.max(0, this.currentHealth - damage);
        
        // Update boss bar for all players
        this.activePlayers.forEach(p -> RaidDenNetworkMessages.SYNC_HEALTH.accept(p, this.currentHealth / this.maxHealth));
        this.bossEvent.setProgress(this.currentHealth / this.maxHealth);
        
        // Check if raid is complete
        if (this.currentHealth <= 0) {
            this.queueStopRaid();
        }
    }
    
    /**
     * Called when player flees or loses - apply partial damage based on what they dealt
     */
    public void applyPartialDamage(ServerPlayer player) {
        float lastHp = this.damageCache.getOrDefault(player.getUUID(), this.initMaxHealth);
        float damageDealt = this.initMaxHealth - lastHp;
        if (damageDealt > 0) {
            this.applyBattleDamage(player, damageDealt);
        }
        // Reset for next battle
        this.damageCache.put(player.getUUID(), this.initMaxHealth);
    }

    public boolean hasFailed(ServerPlayer player) {
        return this.failedPlayers.contains(player.getUUID());
    }

    public void tick() {
        this.runQueue.removeIf(DelayedRunnable::tick);
        
        // Keep boss entity alive while raid HP > 0
        // Restore to normal Pokemon HP (initMaxHealth), not the multiplied global HP
        if (this.currentHealth > 0 && this.bossEntity.getHealth() <= 1) {
            this.bossEntity.setHealth(this.initMaxHealth);
            this.bossEntity.getPokemon().setCurrentHealth((int) this.initMaxHealth);
        }
        
        // Raid timer
        if (!this.activePlayers.isEmpty()) {
            this.raidTimerTicks--;
            
            // Update boss bar with time remaining every second
            if (this.raidTimerTicks % 20 == 0) {
                int remainingSeconds = this.raidTimerTicks / 20;
                int minutes = remainingSeconds / 60;
                int seconds = remainingSeconds % 60;
                String timeStr = String.format("%d:%02d", minutes, seconds);
                
                MutableComponent name = ((MutableComponent) this.bossEntity.getName())
                    .append(Component.literal(" - " + timeStr).withStyle(ChatFormatting.AQUA))
                    .withStyle(ChatFormatting.BOLD);
                this.bossEvent.setName(name);
                
                // Broadcast time warnings
                if (remainingSeconds == 300 || remainingSeconds == 180 || 
                    remainingSeconds == 60 || remainingSeconds == 30 || 
                    remainingSeconds == 10 || (remainingSeconds <= 5 && remainingSeconds > 0)) {
                    Component warning = Component.translatable(
                        remainingSeconds >= 60 ? "message.cobblemonraiddens.coop_raid.time_warning_minutes" : "message.cobblemonraiddens.coop_raid.time_warning_seconds",
                        remainingSeconds >= 60 ? remainingSeconds / 60 : remainingSeconds
                    ).withStyle(remainingSeconds <= 10 ? ChatFormatting.RED : ChatFormatting.GOLD);
                    this.activePlayers.forEach(p -> p.sendSystemMessage(warning));
                }
            }
            
            // Time's up - raid fails
            if (this.raidTimerTicks <= 0) {
                Component defeatMsg = Component.translatable("message.cobblemonraiddens.coop_raid.defeat",
                    (int) this.currentHealth, (int) this.maxHealth)
                    .withStyle(ChatFormatting.RED).withStyle(ChatFormatting.BOLD);
                this.activePlayers.forEach(p -> p.sendSystemMessage(defeatMsg));
                this.queueStopRaid(false);
            }
        }
    }

    public void queueStopRaid() {
        this.queueStopRaid(true);
    }

    public void queueStopRaid(boolean raidSuccess) {
        this.runQueue.add(new DelayedRunnable(() -> this.stopRaid(raidSuccess), 60));
    }

    public void stopRaid(boolean raidSuccess) {
        this.bossEvent.setVisible(false);
        this.bossEvent.removeAllPlayers();
        if (raidSuccess) this.bossEntity.setHealth(0f);
        RaidHelper.ACTIVE_RAIDS.remove(((IRaidAccessor) this.bossEntity).getRaidId());
        this.battles.forEach(PokemonBattle::stop);
        if (this.raidBoss == null) return;

        if (raidSuccess) this.handleSuccess();
        else this.handleFailed();
    }

    private void handleSuccess() {
        int catches = this.raidBoss.getMaxCatches();
        List<ServerPlayer> success;
        List<ServerPlayer> failed;
        if (catches < 0 || this.activePlayers.size() < catches) {
            success = this.activePlayers;
            failed = List.of();
        }
        else if (catches == 0) {
            success = List.of();
            failed = this.activePlayers;
        }
        else {
            Collections.shuffle(this.activePlayers);
            success = this.activePlayers.subList(0, catches);
            failed = this.activePlayers.subList(catches, this.activePlayers.size());
        }

        Pokemon cachedReward;
        if (CobblemonRaidDens.CONFIG.sync_rewards) {
            cachedReward = this.raidBoss.getRewardPokemon(null);
            cachedReward.setShiny(this.bossEntity.getPokemon().getShiny());
            cachedReward.setAbility$common(this.bossEntity.getPokemon().getAbility());
            cachedReward.setGender(this.bossEntity.getPokemon().getGender());
            cachedReward.setNature(this.bossEntity.getPokemon().getNature());
        } else {
            cachedReward = null;
        }

        success.forEach(player -> {
            new RewardHandler(this.raidBoss, player, true, cachedReward).sendRewardMessage();
            RaidEvents.RAID_END.emit(new RaidEndEvent(player, this.raidBoss, this.bossEntity.getPokemon(), true));
        });
        failed.forEach(player -> {
            new RewardHandler(this.raidBoss, player, false).sendRewardMessage();
            RaidEvents.RAID_END.emit(new RaidEndEvent(player, this.raidBoss, this.bossEntity.getPokemon(), true));
        });
    }

    private void handleFailed() {
        this.activePlayers.forEach(player -> {
            player.sendSystemMessage(Component.translatable("message.cobblemonraiddens.raid.raid_fail"));
            RaidEvents.RAID_END.emit(new RaidEndEvent(player, this.raidBoss, this.bossEntity.getPokemon(), false));
        });
    }

    public RaidBoss getRaidBoss() {
        return this.raidBoss;
    }

    public void runScriptByTurn(PokemonBattle battle, int turn) {
        String func = this.scriptByTurn.remove(turn);
        if (func == null) return;
        ((IRaidBattle) battle).addToQueue(INSTRUCTION_MAP.get(func));
    }

    public void runScriptByHp(double hpRatio) {
        this.scriptByHp.tailMap(hpRatio, true)
            .values()
            .forEach(func -> this.battles.forEach(battle -> ((IRaidBattle) battle).addToQueue(INSTRUCTION_MAP.get(func))));

        this.scriptByHp.keySet().removeIf(hp -> hp >= hpRatio);
    }

    public boolean runCheer(ServerPlayer player, PokemonBattle oBattle, BagItem bagItem, String data) {
        int cheersLeft = this.cheersLeft.getOrDefault(player.getUUID(), 0);
        if (cheersLeft <= 0) return false;
        this.cheersLeft.put(player.getUUID(), --cheersLeft);

        this.cheer(oBattle, bagItem, data, false);
        for (PokemonBattle b : this.battles) {
            if (b == oBattle) continue;
            ((IRaidBattle) b).addToQueue((raid, battle) -> raid.cheer(battle, bagItem, data, true));
        }
        return true;
    }

    public void playerJoin(PokemonBattle battle, ServerPlayer player, Player newPlayer) {
        BattleActor side1 = battle.getSide1().getActors()[0];
        BattleActor side2 = battle.getSide2().getActors()[0];
        List<ActiveBattlePokemon> target = side2.getActivePokemon();
        if (side1.getRequest() == null || side2.getRequest() == null) return;
        else if (target.isEmpty() || target.getFirst().getBattlePokemon() == null) return;
        BattlePokemon bp = target.getFirst().getBattlePokemon();
        String data = String.format("%s %s", (int) this.currentHealth, newPlayer.getName().getString());
        this.sendAction(side1, side2, new BagItemActionResponse(new PlayerJoinBagItem(), bp, data));
        this.damageCache.put(player.getUUID(), (float) Math.floor(this.currentHealth));
    }

    public void cheer(PokemonBattle battle, BagItem bagItem, String data, boolean skipEnemyAction) {
        BattleActor side1 = battle.getSide1().getActors()[0];
        BattleActor side2 = battle.getSide2().getActors()[0];
        List<ActiveBattlePokemon> target = side1.getActivePokemon();
        if (side1.getRequest() == null || side2.getRequest() == null || target.isEmpty() || target.getFirst().getBattlePokemon() == null) return;
        if (bagItem instanceof CheerBagItem cheerBagItem && cheerBagItem.cheerType() == CheerBagItem.CheerType.HEAL && target.getFirst().getBattlePokemon().getEntity() instanceof PokemonEntity entity) {
            entity.playSound(CobblemonSounds.MEDICINE_HERB_USE, 1f, 1f);
        }
        this.sendAction(side1, side2,new BagItemActionResponse(bagItem, target.getFirst().getBattlePokemon(), data), skipEnemyAction);
    }

    private void changeBossStat(@NotNull PokemonBattle battle, Stat stat, int stages) {
        BattleActor side1 = battle.getSide1().getActors()[0];
        BattleActor side2 = battle.getSide2().getActors()[0];
        List<ActiveBattlePokemon> target = side2.getActivePokemon();
        if (side1.getRequest() == null || side2.getRequest() == null) return;
        else if (target.isEmpty() || target.getFirst().getBattlePokemon() == null) return;
        BattlePokemon bp = target.getFirst().getBattlePokemon();
        String key = bp.getUuid().toString();
        this.sendAction(side1, side2, new BagItemActionResponse(new StatChangeBagItem(stat, stages), bp, key));
    }

    private void changePlayerStat(@NotNull PokemonBattle battle, Stat stat, int stages) {
        BattleActor side1 = battle.getSide1().getActors()[0];
        BattleActor side2 = battle.getSide2().getActors()[0];
        List<ActiveBattlePokemon> target = side1.getActivePokemon();
        List<ActiveBattlePokemon> origin = side2.getActivePokemon();
        if (side1.getRequest() == null || side2.getRequest() == null) return;
        else if (target.isEmpty() || target.getFirst().getBattlePokemon() == null) return;
        else if (origin.isEmpty() || origin.getFirst().getBattlePokemon() == null) return;
        BattlePokemon bp = target.getFirst().getBattlePokemon();
        String key = origin.getFirst().getBattlePokemon().getUuid().toString();
        this.sendAction(side1, side2, new BagItemActionResponse(new StatChangeBagItem(stat, stages), bp, key));
    }

    private void clearBossStats(@NotNull PokemonBattle battle) {
        BattleActor side1 = battle.getSide1().getActors()[0];
        BattleActor side2 = battle.getSide2().getActors()[0];
        List<ActiveBattlePokemon> target = side2.getActivePokemon();
        if (side1.getRequest() == null || side2.getRequest() == null) return;
        else if (target.isEmpty() || target.getFirst().getBattlePokemon() == null) return;
        BattlePokemon bp = target.getFirst().getBattlePokemon();
        String key = bp.getUuid().toString();
        this.sendAction(side1, side2, new BagItemActionResponse(RaidBagItems.CLEAR_BOSS, bp, key));
    }

    private void clearPlayerStats(@NotNull PokemonBattle battle) {
        BattleActor side1 = battle.getSide1().getActors()[0];
        BattleActor side2 = battle.getSide2().getActors()[0];
        List<ActiveBattlePokemon> target = side1.getActivePokemon();
        List<ActiveBattlePokemon> origin = side2.getActivePokemon();
        if (side1.getRequest() == null || side2.getRequest() == null) return;
        else if (target.isEmpty() || target.getFirst().getBattlePokemon() == null) return;
        else if (origin.isEmpty() || origin.getFirst().getBattlePokemon() == null) return;
        BattlePokemon bp = target.getFirst().getBattlePokemon();
        String key = origin.getFirst().getBattlePokemon().getUuid().toString();
        this.sendAction(side1, side2, new BagItemActionResponse(RaidBagItems.CLEAR_PLAYER, bp, key));
    }

    private void useSimpleBagItem(@NotNull PokemonBattle battle, BagItem item) {
        BattleActor side1 = battle.getSide1().getActors()[0];
        BattleActor side2 = battle.getSide2().getActors()[0];
        List<ActiveBattlePokemon> target = side2.getActivePokemon();
        if (side1.getRequest() == null || side2.getRequest() == null) return;
        else if (target.isEmpty() || target.getFirst().getBattlePokemon() == null) return;
        BattlePokemon bp = target.getFirst().getBattlePokemon();
        this.sendAction(side1, side2, new BagItemActionResponse(item, bp, null));
    }

    private void sendAction(BattleActor side1, BattleActor side2, ShowdownActionResponse response) {
        this.sendAction(side1, side2, response, true);
    }

    private void sendAction(BattleActor side1, BattleActor side2, ShowdownActionResponse response, boolean skipEnemyAction) {
        side1.getResponses().add(response);
        side1.setMustChoose(false);
        if (skipEnemyAction) {
            side2.getResponses().addFirst(PassActionResponse.INSTANCE);
            side2.setMustChoose(false);
        }
        side1.getBattle().checkForInputDispatch();
        side1.sendUpdate(new BattleApplyPassResponsePacket());
    }

    private static class DelayedRunnable {
        private final Runnable runnable;
        private final int delay;
        private int tick;
        private final boolean repeat;

        public DelayedRunnable(Runnable runnable, int delay, boolean repeat) {
            this.runnable = runnable;
            this.delay = delay;
            this.tick = 0;
            this.repeat = repeat;
        }

        public DelayedRunnable(Runnable runnable, int delay) {
            this(runnable, delay, false);
        }

        public boolean tick() {
            if (++this.tick < this.delay) return false;
            this.runnable.run();
            if (this.repeat) this.tick = 0;
            return !this.repeat;
        }
    }

    static {
        INSTRUCTION_MAP.put("RESET_BOSS", RaidInstance::clearBossStats);
        INSTRUCTION_MAP.put("RESET_PLAYER", RaidInstance::clearPlayerStats);

        INSTRUCTION_MAP.put("BOSS_ATK_1", (r, b) -> r.changeBossStat(b, Stats.ATTACK, 1));
        INSTRUCTION_MAP.put("BOSS_ATK_2", (r, b) -> r.changeBossStat(b, Stats.ATTACK, 2));
        INSTRUCTION_MAP.put("BOSS_DEF_1", (r, b) -> r.changeBossStat(b, Stats.DEFENCE, 1));
        INSTRUCTION_MAP.put("BOSS_DEF_2", (r, b) -> r.changeBossStat(b, Stats.DEFENCE, 2));
        INSTRUCTION_MAP.put("BOSS_SPA_1", (r, b) -> r.changeBossStat(b, Stats.SPECIAL_ATTACK, 1));
        INSTRUCTION_MAP.put("BOSS_SPA_2", (r, b) -> r.changeBossStat(b, Stats.SPECIAL_ATTACK, 2));
        INSTRUCTION_MAP.put("BOSS_SPD_1", (r, b) -> r.changeBossStat(b, Stats.SPECIAL_DEFENCE, 1));
        INSTRUCTION_MAP.put("BOSS_SPD_2", (r, b) -> r.changeBossStat(b, Stats.SPECIAL_DEFENCE, 2));
        INSTRUCTION_MAP.put("BOSS_SPE_1", (r, b) -> r.changeBossStat(b, Stats.SPEED, 1));
        INSTRUCTION_MAP.put("BOSS_SPE_2", (r, b) -> r.changeBossStat(b, Stats.SPEED, 2));
        INSTRUCTION_MAP.put("BOSS_ACC_1", (r, b) -> r.changeBossStat(b, Stats.ACCURACY, 1));
        INSTRUCTION_MAP.put("BOSS_ACC_2", (r, b) -> r.changeBossStat(b, Stats.ACCURACY, 2));
        INSTRUCTION_MAP.put("BOSS_EVA_1", (r, b) -> r.changeBossStat(b, Stats.EVASION, 1));
        INSTRUCTION_MAP.put("BOSS_EVA_2", (r, b) -> r.changeBossStat(b, Stats.EVASION, 2));

        INSTRUCTION_MAP.put("PLAYER_ATK_1", (r, b) -> r.changePlayerStat(b, Stats.ATTACK, -1));
        INSTRUCTION_MAP.put("PLAYER_ATK_2", (r, b) -> r.changePlayerStat(b, Stats.ATTACK, -2));
        INSTRUCTION_MAP.put("PLAYER_DEF_1", (r, b) -> r.changePlayerStat(b, Stats.DEFENCE, -1));
        INSTRUCTION_MAP.put("PLAYER_DEF_2", (r, b) -> r.changePlayerStat(b, Stats.DEFENCE, -2));
        INSTRUCTION_MAP.put("PLAYER_SPA_1", (r, b) -> r.changePlayerStat(b, Stats.SPECIAL_ATTACK, -1));
        INSTRUCTION_MAP.put("PLAYER_SPA_2", (r, b) -> r.changePlayerStat(b, Stats.SPECIAL_ATTACK, -2));
        INSTRUCTION_MAP.put("PLAYER_SPD_1", (r, b) -> r.changePlayerStat(b, Stats.SPECIAL_DEFENCE, -1));
        INSTRUCTION_MAP.put("PLAYER_SPD_2", (r, b) -> r.changePlayerStat(b, Stats.SPECIAL_DEFENCE, -2));
        INSTRUCTION_MAP.put("PLAYER_SPE_1", (r, b) -> r.changePlayerStat(b, Stats.SPEED, -1));
        INSTRUCTION_MAP.put("PLAYER_SPE_2", (r, b) -> r.changePlayerStat(b, Stats.SPEED, -2));
        INSTRUCTION_MAP.put("PLAYER_ACC_1", (r, b) -> r.changePlayerStat(b, Stats.ACCURACY, -1));
        INSTRUCTION_MAP.put("PLAYER_ACC_2", (r, b) -> r.changePlayerStat(b, Stats.ACCURACY, -2));
        INSTRUCTION_MAP.put("PLAYER_EVA_1", (r, b) -> r.changePlayerStat(b, Stats.EVASION, -1));
        INSTRUCTION_MAP.put("PLAYER_EVA_2", (r, b) -> r.changePlayerStat(b, Stats.EVASION, -2));

        INSTRUCTION_MAP.put("SET_RAIN", (r, b) -> r.useSimpleBagItem(b, RaidBagItems.SET_RAIN));
        INSTRUCTION_MAP.put("SET_SANDSTORM", (r, b) -> r.useSimpleBagItem(b, RaidBagItems.SET_SANDSTORM));
        INSTRUCTION_MAP.put("SET_SNOW", (r, b) -> r.useSimpleBagItem(b, RaidBagItems.SET_SNOW));
        INSTRUCTION_MAP.put("SET_SUN", (r, b) -> r.useSimpleBagItem(b, RaidBagItems.SET_SUN));

        INSTRUCTION_MAP.put("SET_ELECTRIC_TERRAIN", (r, b) -> r.useSimpleBagItem(b, RaidBagItems.SET_ELECTRIC_TERRAIN));
        INSTRUCTION_MAP.put("SET_GRASSY_TERRAIN", (r, b) -> r.useSimpleBagItem(b, RaidBagItems.SET_GRASSY_TERRAIN));
        INSTRUCTION_MAP.put("SET_MISTY_TERRAIN", (r, b) -> r.useSimpleBagItem(b, RaidBagItems.SET_MISTY_TERRAIN));
        INSTRUCTION_MAP.put("SET_PSYCHIC_TERRAIN", (r, b) -> r.useSimpleBagItem(b, RaidBagItems.SET_PSYCHIC_TERRAIN));
    }
}
