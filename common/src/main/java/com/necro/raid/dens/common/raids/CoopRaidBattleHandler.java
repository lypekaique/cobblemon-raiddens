package com.necro.raid.dens.common.raids;

import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.battles.ActiveBattlePokemon;
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.necro.raid.dens.common.util.IRaidAccessor;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Handles battle events for Cooperative Raids
 * 
 * In coop raids:
 * - Players fight "normal" versions of the Pokémon
 * - Each victory reduces the shared boss HP
 * - Players can continue fighting until boss HP reaches 0 or time runs out
 */
public class CoopRaidBattleHandler {
    
    // Track which players are currently in coop raid battles
    private static final Map<UUID, CoopRaidSession> PLAYER_COOP_BATTLES = new HashMap<>();
    
    /**
     * Register a player as being in a coop raid battle
     */
    public static void registerPlayerBattle(ServerPlayer player, CoopRaidSession session) {
        PLAYER_COOP_BATTLES.put(player.getUUID(), session);
    }
    
    /**
     * Unregister a player from coop raid battle tracking
     */
    public static void unregisterPlayerBattle(ServerPlayer player) {
        PLAYER_COOP_BATTLES.remove(player.getUUID());
    }
    
    /**
     * Check if a player is in a coop raid battle
     */
    public static boolean isInCoopRaidBattle(ServerPlayer player) {
        return PLAYER_COOP_BATTLES.containsKey(player.getUUID());
    }
    
    /**
     * Get the coop raid session for a player's current battle
     */
    public static CoopRaidSession getPlayerCoopRaidSession(ServerPlayer player) {
        return PLAYER_COOP_BATTLES.get(player.getUUID());
    }
    
    /**
     * Handle a battle victory event for coop raids
     * Called when a player defeats the opposing Pokémon
     * 
     * @param battle The battle that ended
     * @param winner The winning battle actor
     * @return true if this was a coop raid battle that was handled
     */
    public static boolean handleBattleVictory(PokemonBattle battle, BattleActor winner) {
        // Get the player from the battle
        if (battle.getPlayers().isEmpty()) return false;
        ServerPlayer player = battle.getPlayers().getFirst();
        
        // Check if this player is in a coop raid
        CoopRaidSession session = PLAYER_COOP_BATTLES.get(player.getUUID());
        if (session == null || !session.isRaidActive()) return false;
        
        // Verify the player won (not the opponent)
        if (!isPlayerWinner(battle, winner, player)) return false;
        
        // Player won against the normal Pokémon - reduce boss HP
        session.onPlayerVictory(player);
        
        // Unregister this battle (player can start a new one)
        unregisterPlayerBattle(player);
        
        return true;
    }
    
    /**
     * Handle a battle flee event for coop raids
     * In coop raids, fleeing doesn't count as a failure - player can just try again
     * 
     * @param battle The battle that was fled from
     * @return true if this was a coop raid battle
     */
    public static boolean handleBattleFlee(PokemonBattle battle) {
        if (battle.getPlayers().isEmpty()) return false;
        ServerPlayer player = battle.getPlayers().getFirst();
        
        CoopRaidSession session = PLAYER_COOP_BATTLES.get(player.getUUID());
        if (session == null) return false;
        
        // Just unregister - no penalty for fleeing in coop raids
        unregisterPlayerBattle(player);
        return true;
    }
    
    /**
     * Handle a battle loss for coop raids
     * In coop raids, losing doesn't end the raid - player can heal and try again
     * 
     * @param battle The battle that was lost
     * @return true if this was a coop raid battle
     */
    public static boolean handleBattleLoss(PokemonBattle battle) {
        if (battle.getPlayers().isEmpty()) return false;
        ServerPlayer player = battle.getPlayers().getFirst();
        
        CoopRaidSession session = PLAYER_COOP_BATTLES.get(player.getUUID());
        if (session == null) return false;
        
        // Just unregister - no penalty for losing in coop raids
        unregisterPlayerBattle(player);
        return true;
    }
    
    /**
     * Check if the player was the winner of the battle
     */
    private static boolean isPlayerWinner(PokemonBattle battle, BattleActor winner, ServerPlayer player) {
        // The winner should be on the player's side (side1)
        return battle.getSide1().getActors().length > 0 && 
               battle.getSide1().getActors()[0] == winner;
    }
    
    /**
     * Check if a battle involves a coop raid boss
     */
    public static boolean isCoopRaidBattle(PokemonBattle battle) {
        try {
            ActiveBattlePokemon activePokemon = battle.getSide2().getActivePokemon().getFirst();
            if (activePokemon == null) return false;
            
            BattlePokemon battlePokemon = activePokemon.getBattlePokemon();
            if (battlePokemon == null || battlePokemon.getEntity() == null) return false;
            
            PokemonEntity entity = (PokemonEntity) battlePokemon.getEntity();
            return ((IRaidAccessor) entity).isInCoopRaid();
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Get the coop raid session from a battle
     */
    public static CoopRaidSession getSessionFromBattle(PokemonBattle battle) {
        try {
            ActiveBattlePokemon activePokemon = battle.getSide2().getActivePokemon().getFirst();
            if (activePokemon == null) return null;
            
            BattlePokemon battlePokemon = activePokemon.getBattlePokemon();
            if (battlePokemon == null || battlePokemon.getEntity() == null) return null;
            
            PokemonEntity entity = (PokemonEntity) battlePokemon.getEntity();
            return ((IRaidAccessor) entity).getCoopRaidSession();
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * Cleanup when a coop raid ends
     */
    public static void onRaidEnd(CoopRaidSession session) {
        // Remove all players that were in battles for this session
        PLAYER_COOP_BATTLES.entrySet().removeIf(entry -> entry.getValue() == session);
    }
}
