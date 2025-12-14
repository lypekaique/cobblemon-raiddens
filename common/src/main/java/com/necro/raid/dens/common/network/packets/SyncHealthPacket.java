package com.necro.raid.dens.common.network.packets;

import com.necro.raid.dens.common.CobblemonRaidDens;
import com.necro.raid.dens.common.client.ClientManager;
import com.necro.raid.dens.common.network.ClientPacket;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

public record SyncHealthPacket(float healthRatio) implements CustomPacketPayload, ClientPacket {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(CobblemonRaidDens.MOD_ID, "sync_health");
    public static final Type<SyncHealthPacket> PACKET_TYPE = new Type<>(ID);
    public static final StreamCodec<FriendlyByteBuf, SyncHealthPacket> CODEC = StreamCodec.ofMember(SyncHealthPacket::write, SyncHealthPacket::read);

    public void write(FriendlyByteBuf buf) {
        buf.writeFloat(this.healthRatio);
    }

    public static SyncHealthPacket read(FriendlyByteBuf buf) {
        return new SyncHealthPacket(buf.readFloat());
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return PACKET_TYPE;
    }

    @Override
    public void handleClient() {
        // Boss HP sync is handled by ServerBossEvent (the center bar)
        // We do NOT sync the Pokemon battle HP bar (right side Cobblemon UI)
        // The Pokemon battle HP should behave normally during battle
        // This packet only updates the boss bar, not the Pokemon's battle HP
        ClientManager.syncBossHealthRatio(this.healthRatio);
    }
}
