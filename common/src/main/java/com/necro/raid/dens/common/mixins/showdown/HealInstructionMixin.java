package com.necro.raid.dens.common.mixins.showdown;

import com.cobblemon.mod.common.api.battles.interpreter.BattleMessage;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.battles.dispatch.InterpreterInstruction;
import com.cobblemon.mod.common.battles.interpreter.instructions.HealInstruction;
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;
import com.necro.raid.dens.common.raids.RaidInstance;
import com.necro.raid.dens.common.util.IRaidAccessor;
import com.necro.raid.dens.common.util.IRaidBattle;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HealInstruction.class)
public abstract class HealInstructionMixin implements InterpreterInstruction {
    @Final
    @Shadow(remap = false)
    private BattleMessage publicMessage;

    @Final
    @Shadow(remap = false)
    private BattleMessage privateMessage;

    @Final
    @Shadow(remap = false)
    private BattleActor actor;

    /**
     * Intercept heal to track raid progress.
     * IMPORTANT: We do NOT cancel the original instruction - let Cobblemon process normally
     * so the Pokemon battle HP bar (right side) updates correctly.
     */
    @Inject(method = "invoke", at = @At("RETURN"), remap = false)
    private void invokeInject(PokemonBattle battle, CallbackInfo ci) {
        if (!((IRaidBattle) battle).isRaidBattle()) return;
        RaidInstance raidInstance = ((IRaidBattle) battle).getRaidBattle();
        BattlePokemon battlePokemon = this.publicMessage.battlePokemon(0, this.actor.battle);
        if (battlePokemon == null || battlePokemon.getEntity() == null) return;
        else if (!((IRaidAccessor) battlePokemon.getEntity()).isRaidBoss()) return;

        String args = this.privateMessage.argumentAt(1);
        if (args == null) return;
        
        float remainingHealth = Float.parseFloat(args.split("/")[0]);
        ServerPlayer player = battle.getPlayers().getFirst();
        
        // Track HP for this battle - the Pokemon HP bar updates normally via Cobblemon
        raidInstance.trackBattleHp(player, remainingHealth);
    }
}
