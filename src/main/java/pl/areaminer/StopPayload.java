package pl.areaminer;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Pakiet klient -> serwer: "zatrzymaj kopanie" (wysylany po otwarciu czatu/ESC). */
public record StopPayload() implements CustomPayload {
    public static final Id<StopPayload> ID = new Id<>(Identifier.of(AreaMinerMod.MOD_ID, "stop"));
    public static final PacketCodec<RegistryByteBuf, StopPayload> CODEC = PacketCodec.unit(new StopPayload());

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
