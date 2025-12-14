package com.necro.raid.dens.common.mixins.showdown;

import com.cobblemon.mod.common.api.battles.interpreter.BattleMessage;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.battles.dispatch.ActionEffectInstruction;
import com.cobblemon.mod.common.battles.dispatch.InstructionSet;
import com.cobblemon.mod.common.battles.interpreter.instructions.DamageInstruction;
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
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

@Mixin(DamageInstruction.class)
public abstract class DamageInstructionMixin implements ActionEffectInstruction {
    @Final
    @Shadow(remap = false)
    private BattleMessage publicMessage;

    @Final
    @Shadow(remap = false)
    private BattleMessage privateMessage;

    @Final
    @Shadow(remap = false)
    private InstructionSet instructionSet;

    @Final
    @Shadow(remap = false)
    private BattleActor actor;

    /**
     * Intercept damage to track raid progress.
     * IMPORTANT: We do NOT cancel the original instruction - let Cobblemon process normally
     * so the Pokemon battle HP bar (right side) updates correctly.
     * 
     * The Boss HP bar (center) is managed separately and only decreases when Pokemon is defeated.
     */
    @Inject(method = "postActionEffect", at = @At("RETURN"), remap = false)
    private void postActionEffectInject(PokemonBattle battle, CallbackInfo ci) {
        if (!((IRaidBattle) battle).isRaidBattle()) return;
        RaidInstance raidInstance = ((IRaidBattle) battle).getRaidBattle();
        BattlePokemon battlePokemon = this.publicMessage.battlePokemon(0, this.actor.battle);
        if (battlePokemon == null || battlePokemon.getEntity() == null) return;
        else if (!((IRaidAccessor) battlePokemon.getEntity()).isRaidBoss()) return;

        String args = this.privateMessage.argumentAt(1);
        if (args == null) return;
        
        String newHealth = args.split(" ")[0];
        boolean causedFaint = newHealth.equals("0");

        ServerPlayer player = battle.getPlayers().getFirst();
        PokemonEntity bossEntity = battlePokemon.getEntity();
        
        if (causedFaint) {
            // Player defeated the Pokemon in this battle
            // Apply full Pokemon HP as damage to the Boss HP bar (center)
            raidInstance.applyBattleDamage(player, raidInstance.getInitMaxHealth());
            
            // CRITICAL: Restore the boss entity's HP IMMEDIATELY to prevent death
            // The boss entity is visual-only, it should never actually die from battles
            if (bossEntity != null) {
                bossEntity.setHealth(bossEntity.getMaxHealth());
                bossEntity.getPokemon().setCurrentHealth(bossEntity.getPokemon().getMaxHealth());
            }
            
            // Remove battle and reset tracking - allows the battle to close properly
            raidInstance.onPlayerBattleWin(player, battle);
        }
        else {
            // During battle: just track current HP for potential flee/loss calculation
            // The Pokemon HP bar (right side) is updated normally by Cobblemon
            float remainingHealth = Float.parseFloat(newHealth.split("/")[0]);
            raidInstance.trackBattleHp(player, remainingHealth);
        }
    }
}
