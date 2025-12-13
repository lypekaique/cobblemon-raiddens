package com.necro.raid.dens.common.commands;

import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.necro.raid.dens.common.CobblemonRaidDens;
import com.necro.raid.dens.common.raids.CoopRaidBattleHandler;
import com.necro.raid.dens.common.raids.CoopRaidSession;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;

/**
 * Commands for managing Cooperative Raids
 * 
 * Usage:
 * /coopraid start <pokemon> [multiplier] [duration] - Start a coop raid with specified Pokémon
 * /coopraid join - Join an active coop raid
 * /coopraid leave - Leave the current coop raid
 * /coopraid status - Show current coop raid status
 * /coopraid stop - Force stop the current coop raid (admin only)
 */
public class CoopRaidCommands {
    
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("coopraid")
            .then(Commands.literal("start")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("pokemon", StringArgumentType.string())
                    .executes(context -> startCoopRaid(
                        context,
                        StringArgumentType.getString(context, "pokemon"),
                        8, // Default multiplier for command-based coop raids
                        CobblemonRaidDens.CONFIG.coop_raid_duration_seconds
                    ))
                    .then(Commands.argument("multiplier", IntegerArgumentType.integer(1, 100))
                        .executes(context -> startCoopRaid(
                            context,
                            StringArgumentType.getString(context, "pokemon"),
                            IntegerArgumentType.getInteger(context, "multiplier"),
                            CobblemonRaidDens.CONFIG.coop_raid_duration_seconds
                        ))
                        .then(Commands.argument("duration", IntegerArgumentType.integer(60, 3600))
                            .executes(context -> startCoopRaid(
                                context,
                                StringArgumentType.getString(context, "pokemon"),
                                IntegerArgumentType.getInteger(context, "multiplier"),
                                IntegerArgumentType.getInteger(context, "duration")
                            ))
                        )
                    )
                )
            )
            .then(Commands.literal("startat")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("pos", Vec3Argument.vec3())
                    .then(Commands.argument("pokemon", StringArgumentType.string())
                        .executes(context -> startCoopRaidAt(
                            context,
                            Vec3Argument.getVec3(context, "pos"),
                            StringArgumentType.getString(context, "pokemon"),
                            8, // Default multiplier for command-based coop raids
                            CobblemonRaidDens.CONFIG.coop_raid_duration_seconds
                        ))
                        .then(Commands.argument("multiplier", IntegerArgumentType.integer(1, 100))
                            .executes(context -> startCoopRaidAt(
                                context,
                                Vec3Argument.getVec3(context, "pos"),
                                StringArgumentType.getString(context, "pokemon"),
                                IntegerArgumentType.getInteger(context, "multiplier"),
                                CobblemonRaidDens.CONFIG.coop_raid_duration_seconds
                            ))
                            .then(Commands.argument("duration", IntegerArgumentType.integer(60, 3600))
                                .executes(context -> startCoopRaidAt(
                                    context,
                                    Vec3Argument.getVec3(context, "pos"),
                                    StringArgumentType.getString(context, "pokemon"),
                                    IntegerArgumentType.getInteger(context, "multiplier"),
                                    IntegerArgumentType.getInteger(context, "duration")
                                ))
                            )
                        )
                    )
                )
            )
            .then(Commands.literal("join")
                .requires(CommandSourceStack::isPlayer)
                .executes(CoopRaidCommands::joinCoopRaid)
            )
            .then(Commands.literal("leave")
                .requires(CommandSourceStack::isPlayer)
                .executes(CoopRaidCommands::leaveCoopRaid)
            )
            .then(Commands.literal("status")
                .executes(CoopRaidCommands::showStatus)
            )
            .then(Commands.literal("stop")
                .requires(source -> source.hasPermission(2))
                .executes(CoopRaidCommands::stopCoopRaid)
            )
            .then(Commands.literal("addplayers")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("players", EntityArgument.players())
                    .executes(CoopRaidCommands::addPlayersToRaid)
                )
            )
        );
    }
    
    @SuppressWarnings("unused")
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext commandBuildContext, Commands.CommandSelection commandSelection) {
        register(dispatcher);
    }
    
    private static int startCoopRaid(CommandContext<CommandSourceStack> context, String pokemonSpec, int multiplier, int durationSeconds) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFailure(Component.literal("This command must be run by a player."));
            return 0;
        }
        
        Vec3 pos = player.position();
        return startCoopRaidAt(context, pos, pokemonSpec, multiplier, durationSeconds);
    }
    
    private static int startCoopRaidAt(CommandContext<CommandSourceStack> context, Vec3 pos, String pokemonSpec, int multiplier, int durationSeconds) {
        ServerLevel level = context.getSource().getLevel();
        
        // Check if there's already an active coop raid
        if (!CoopRaidSession.ACTIVE_COOP_RAIDS.isEmpty()) {
            context.getSource().sendFailure(Component.literal("There is already an active coop raid. Stop it first with /coopraid stop"));
            return 0;
        }
        
        // Parse Pokémon properties
        PokemonProperties properties = PokemonProperties.Companion.parse(pokemonSpec);
        if (properties.getSpecies() == null || properties.getSpecies().isEmpty()) {
            context.getSource().sendFailure(Component.literal("Invalid Pokémon species: " + pokemonSpec));
            return 0;
        }
        
        // Create the Pokémon entity
        var pokemon = properties.create();
        pokemon.setLevel(50); // Default level for coop raids
        
        // Apply health multiplier to the Pokémon
        int baseHp = pokemon.getMaxHealth();
        int bossHp = baseHp * multiplier;
        
        // Create entity
        PokemonEntity bossEntity = new PokemonEntity(level, pokemon, com.cobblemon.mod.common.CobblemonEntities.POKEMON);
        bossEntity.setPos(pos.x, pos.y, pos.z);
        bossEntity.getPokemon().setCurrentHealth(bossHp);
        
        // Mark as raid boss
        bossEntity.getPokemon().getPersistentData().putBoolean("coop_raid_boss", true);
        bossEntity.setInvulnerable(true); // Boss can't be killed directly
        
        // Spawn the entity
        level.addFreshEntity(bossEntity);
        
        // Create the coop raid session
        int durationTicks = durationSeconds * 20;
        CoopRaidSession session = new CoopRaidSession(bossEntity, multiplier, durationTicks);
        
        // Add the command executor as the first participant if they're a player
        ServerPlayer player = context.getSource().getPlayer();
        if (player != null) {
            session.addPlayer(player);
        }
        
        // Broadcast raid start message
        Component startMessage = Component.translatable("message.cobblemonraiddens.coop_raid.started")
            .withStyle(ChatFormatting.GOLD)
            .withStyle(ChatFormatting.BOLD);
        
        for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
            p.sendSystemMessage(startMessage);
            p.sendSystemMessage(Component.literal("Boss: " + pokemon.getSpecies().getName() + 
                " | HP: " + bossHp + " | Time: " + durationSeconds + "s | Multiplier: " + multiplier + "x")
                .withStyle(ChatFormatting.YELLOW));
            p.sendSystemMessage(Component.literal("Use /coopraid join to participate!")
                .withStyle(ChatFormatting.GREEN));
        }
        
        context.getSource().sendSuccess(() -> Component.literal("Coop raid started successfully!"), true);
        return 1;
    }
    
    private static int joinCoopRaid(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) return 0;
        
        // Check if player is already in a coop raid
        if (CoopRaidSession.isPlayerInCoopRaid(player)) {
            context.getSource().sendFailure(Component.translatable("message.cobblemonraiddens.coop_raid.already_in_raid"));
            return 0;
        }
        
        // Find an active coop raid to join
        if (CoopRaidSession.ACTIVE_COOP_RAIDS.isEmpty()) {
            context.getSource().sendFailure(Component.literal("No active coop raid to join."));
            return 0;
        }
        
        // Join the first active raid
        CoopRaidSession session = CoopRaidSession.ACTIVE_COOP_RAIDS.values().iterator().next();
        if (!session.isRaidActive()) {
            context.getSource().sendFailure(Component.translatable("message.cobblemonraiddens.coop_raid.raid_not_active"));
            return 0;
        }
        
        session.addPlayer(player);
        return 1;
    }
    
    private static int leaveCoopRaid(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) return 0;
        
        CoopRaidSession session = CoopRaidSession.getPlayerCoopRaid(player);
        if (session == null) {
            context.getSource().sendFailure(Component.literal("You are not in a coop raid."));
            return 0;
        }
        
        session.removePlayer(player);
        CoopRaidBattleHandler.unregisterPlayerBattle(player);
        
        player.sendSystemMessage(Component.translatable("message.cobblemonraiddens.coop_raid.player_left", player.getName())
            .withStyle(ChatFormatting.YELLOW));
        return 1;
    }
    
    private static int showStatus(CommandContext<CommandSourceStack> context) {
        if (CoopRaidSession.ACTIVE_COOP_RAIDS.isEmpty()) {
            context.getSource().sendSystemMessage(Component.literal("No active coop raids.").withStyle(ChatFormatting.GRAY));
            return 1;
        }
        
        for (CoopRaidSession session : CoopRaidSession.ACTIVE_COOP_RAIDS.values()) {
            int remainingSeconds = session.getRemainingSeconds();
            int minutes = remainingSeconds / 60;
            int seconds = remainingSeconds % 60;
            
            context.getSource().sendSystemMessage(Component.literal("=== Coop Raid Status ===").withStyle(ChatFormatting.GOLD));
            context.getSource().sendSystemMessage(Component.literal("Boss HP: " + session.getCurrentBossHp() + "/" + session.getMaxBossHp())
                .withStyle(ChatFormatting.RED));
            context.getSource().sendSystemMessage(Component.literal("Time Remaining: " + String.format("%d:%02d", minutes, seconds))
                .withStyle(ChatFormatting.AQUA));
            context.getSource().sendSystemMessage(Component.literal("Players: " + session.getParticipants().size())
                .withStyle(ChatFormatting.GREEN));
            context.getSource().sendSystemMessage(Component.literal("Normal Pokémon HP: " + session.getNormalPokemonHp())
                .withStyle(ChatFormatting.YELLOW));
            context.getSource().sendSystemMessage(Component.literal("Multiplier: " + session.getHealthMultiplier() + "x")
                .withStyle(ChatFormatting.YELLOW));
        }
        
        return 1;
    }
    
    private static int stopCoopRaid(CommandContext<CommandSourceStack> context) {
        if (CoopRaidSession.ACTIVE_COOP_RAIDS.isEmpty()) {
            context.getSource().sendFailure(Component.literal("No active coop raids to stop."));
            return 0;
        }
        
        // Stop all active coop raids
        for (CoopRaidSession session : CoopRaidSession.ACTIVE_COOP_RAIDS.values().toArray(new CoopRaidSession[0])) {
            session.endRaid(false);
        }
        
        context.getSource().sendSuccess(() -> Component.literal("All coop raids have been stopped."), true);
        return 1;
    }
    
    private static int addPlayersToRaid(CommandContext<CommandSourceStack> context) {
        if (CoopRaidSession.ACTIVE_COOP_RAIDS.isEmpty()) {
            context.getSource().sendFailure(Component.literal("No active coop raid."));
            return 0;
        }
        
        CoopRaidSession session = CoopRaidSession.ACTIVE_COOP_RAIDS.values().iterator().next();
        
        try {
            Collection<ServerPlayer> players = EntityArgument.getPlayers(context, "players");
            for (ServerPlayer player : players) {
                if (!session.getParticipants().contains(player)) {
                    session.addPlayer(player);
                }
            }
            context.getSource().sendSuccess(() -> Component.literal("Added " + players.size() + " player(s) to the coop raid."), true);
            return 1;
        } catch (Exception e) {
            context.getSource().sendFailure(Component.literal("Failed to add players: " + e.getMessage()));
            return 0;
        }
    }
}
