package com.necro.raid.dens.common.mixins.raid;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.necro.raid.dens.common.util.IRaidAccessor;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to prevent raid bosses from dying during individual battles.
 * The boss should ONLY die when the global raid HP reaches 0.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    
    @Shadow
    public abstract float getMaxHealth();
    
    /**
     * CRITICAL: Intercept setHealth to prevent raid boss HP from going to 0.
     * This is the main protection - Cobblemon uses setHealth(0) to kill Pokemon.
     */
    @Inject(method = "setHealth", at = @At("HEAD"), cancellable = true)
    private void setHealthInject(float health, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        
        // Only apply to PokemonEntity raid bosses
        if (!(self instanceof PokemonEntity pokemonEntity)) return;
        
        // IMPORTANT: Check if Pokemon is initialized (can be null during entity construction)
        if (pokemonEntity.getPokemon() == null) return;
        
        IRaidAccessor accessor = (IRaidAccessor) pokemonEntity;
        
        // Only protect active raid bosses
        if (!accessor.isRaidBoss() || accessor.getRaidId() == null) return;
        
        // If trying to set health to 0 or below, block unless allowed to die
        if (health <= 0 && !accessor.isAllowedToDie()) {
            // Don't let HP go to 0 - restore to max instead
            // This prevents the boss from dying during individual battles
            ci.cancel();
        }
    }
    
    /**
     * Backup: Also intercept die() in case it's called directly.
     */
    @Inject(method = "die", at = @At("HEAD"), cancellable = true)
    private void dieInject(DamageSource damageSource, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        
        // Only apply to PokemonEntity raid bosses
        if (!(self instanceof PokemonEntity pokemonEntity)) return;
        
        // IMPORTANT: Check if Pokemon is initialized (can be null during entity construction)
        if (pokemonEntity.getPokemon() == null) return;
        
        IRaidAccessor accessor = (IRaidAccessor) pokemonEntity;
        
        // Only protect active raid bosses
        if (!accessor.isRaidBoss() || accessor.getRaidId() == null) return;
        
        // Block death unless explicitly allowed by raid system
        if (!accessor.isAllowedToDie()) {
            ci.cancel();
        }
    }
}
