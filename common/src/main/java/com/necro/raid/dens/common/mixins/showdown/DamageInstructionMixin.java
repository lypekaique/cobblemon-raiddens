package com.necro.raid.dens.common.mixins.showdown;

import com.cobblemon.mod.common.api.battles.interpreter.BattleMessage;
import com.cobblemon.mod.common.api.battles.interpreter.Effect;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.api.pokemon.status.Status;
import com.cobblemon.mod.common.api.pokemon.status.Statuses;
import com.cobblemon.mod.common.battles.dispatch.*;
import com.cobblemon.mod.common.battles.interpreter.instructions.DamageInstruction;
import com.cobblemon.mod.common.battles.interpreter.instructions.MoveInstruction;
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;
import com.cobblemon.mod.common.util.LocalizationUtilsKt;
import com.necro.raid.dens.common.raids.RaidInstance;
import com.necro.raid.dens.common.util.IRaidAccessor;
import com.necro.raid.dens.common.util.IRaidBattle;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

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

    @Inject(method = "postActionEffect", at = @At("HEAD"), cancellable = true, remap = false)
    private void postActionEffectInject(PokemonBattle battle, CallbackInfo ci) {
        if (!((IRaidBattle) battle).isRaidBattle()) return;
        RaidInstance raidInstance = ((IRaidBattle) battle).getRaidBattle();
        BattlePokemon battlePokemon = this.publicMessage.battlePokemon(0, this.actor.battle);
        if (battlePokemon == null || battlePokemon.getEntity() == null) return;
        else if (!((IRaidAccessor) battlePokemon.getEntity()).isRaidBoss()) return;

        String args = this.privateMessage.argumentAt(1);
        assert args != null;
        String newHealth = args.split(" ")[0];
        CauserInstruction lastCauser = this.instructionSet.getMostRecentCauser(this);
        boolean causedFaint = newHealth.equals("0");
        Effect effect = this.privateMessage.effect("from");

        battle.dispatch(() -> {
            Component pokemonName = battlePokemon.getName();
            BattlePokemon source = this.privateMessage.battlePokemonFromOptional(battle, "of");

            if (effect != null) {
                Component lang = null;

                if (Set.of("brn", "psn", "tox").contains(effect.getId())) {
                    Status status = Statuses.INSTANCE.getStatus(effect.getId());
                    if (status != null) lang = LocalizationUtilsKt.battleLang(String.format("status.%s.hurt", status.getName().getPath()), pokemonName);
                }
                else if (Set.of("aftermath", "innardsout").contains(effect.getId())) lang = LocalizationUtilsKt.battleLang("damage.generic", pokemonName);
                else if (Set.of("chloroblast", "steelbeam").contains(effect.getId())) lang = LocalizationUtilsKt.battleLang("damage.mindblown", pokemonName);
                else if (effect.getId().equals("jumpkick")) lang = LocalizationUtilsKt.battleLang("damage.highjumpkick", pokemonName);
                else lang = LocalizationUtilsKt.battleLang("damage." + effect.getId(), pokemonName, source != null ? source.getName() : Component.literal("UNKOWN"));

                if (lang != null) battle.broadcastChatMessage(lang);
            }

            ServerPlayer player = battle.getPlayers().getFirst();
            if (causedFaint) {
                // Player defeated the boss - apply full Pokemon HP as damage to global HP
                raidInstance.applyBattleDamage(player, raidInstance.getInitMaxHealth());
                
                // Check if shared raid HP is depleted
                if (raidInstance.getRemainingHealth() <= 0) {
                    // Raid complete - boss dies
                    battlePokemon.getEffectedPokemon().setCurrentHealth(0);
                } else {
                    // Raid continues - end this battle but keep boss entity alive
                    battlePokemon.getEffectedPokemon().setCurrentHealth(1);
                }
            }
            else {
                // During battle: just track current HP for potential flee/loss
                float remainingHealth = Float.parseFloat(newHealth.split("/")[0]);
                raidInstance.trackBattleHp(player, remainingHealth);
            }

            if (lastCauser instanceof MoveInstruction && ((MoveInstruction) lastCauser).getActionEffect() != null && !causedFaint) {
                return new UntilDispatch(() -> ((MoveInstruction) lastCauser).getFuture().isDone());
            }
            else if (causedFaint) return DispatchResultKt.getGO();
            else return new UntilDispatch(() -> !getHolds().contains("effects"));
        });
        ci.cancel();
    }
}
