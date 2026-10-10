package com.nguyendevs.freesia.neoforge.util;

import io.netty.buffer.ByteBuf;
import java.util.UUID;

/** Read only the 1.21.1 packet prefix. Never decode modded registries or install a PE wrapper. */
public final class EntityPacketReader {
    private EntityPacketReader() {}
    public record Spawn(int entityId, UUID uuid) {}
    public static Spawn spawn(ByteBuf packet) {
        ByteBuf in = packet.duplicate();
        int id = BufUtil.readVarInt(in);
        return new Spawn(id, new UUID(in.readLong(), in.readLong()));
    }
    public static int join(ByteBuf packet) { return packet.duplicate().readInt(); }
    public static int[] destroyed(ByteBuf packet) {
        ByteBuf in = packet.duplicate();
        int count = BufUtil.readVarInt(in);
        if (count < 0 || count > in.readableBytes()) throw new IllegalArgumentException("Invalid entity count");
        int[] ids = new int[count];
        for (int i = 0; i < count; i++) ids[i] = BufUtil.readVarInt(in);
        return ids;
    }
}
