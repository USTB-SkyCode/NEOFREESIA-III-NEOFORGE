package com.nguyendevs.freesia.neoforge.proxy;

import com.nguyendevs.freesia.neoforge.YsmConstants;
import com.nguyendevs.freesia.neoforge.mapper.MapperManager;
import com.nguyendevs.freesia.neoforge.util.BufUtil;
import com.velocitypowered.api.proxy.Player;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;

public class YsmPacketProxy {
    public enum Kind { PASS, MODIFY, DROP }
    public record Result(Kind kind, byte[] data, boolean broadcast) {
        static Result pass(byte[] data) { return new Result(Kind.PASS, data, false); }
        static Result modify(byte[] data) { return new Result(Kind.MODIFY, data, false); }
        static Result drop() { return new Result(Kind.DROP, null, false); }
    }
    private final Player player;
    private final MapperManager mapper;
    private volatile boolean handshaked;
    public YsmPacketProxy(Player player, MapperManager mapper) { this.player = player; this.mapper = mapper; }

    public Result processS2C(byte[] data) {
        ByteBuf buf = Unpooled.wrappedBuffer(data);
        try {
            int packetId = BufUtil.readVarInt(buf);
            switch (packetId) {
                case YsmConstants.S2C_SET_MODEL, YsmConstants.S2C_ANIMATION -> {
                    int workerId = BufUtil.readVarInt(buf);
                    mapper.routeEntityPacket(player, packetId, workerId, data);
                    return Result.drop();
                }
                case YsmConstants.S2C_MOLANG -> {
                    int count = BufUtil.readVarInt(buf);
                    if (count < 0 || count > buf.readableBytes()) return Result.drop();
                    List<Integer> ids = new ArrayList<>();
                    for (int i = 0; i < count; i++) {
                        Integer mapped = mapper.entityForWorker(player, BufUtil.readVarInt(buf));
                        if (mapped != null) ids.add(mapped);
                    }
                    if (ids.isEmpty()) return Result.drop();
                    ByteBuf out = Unpooled.buffer(data.length);
                    try {
                        BufUtil.writeVarInt(out, packetId);
                        BufUtil.writeVarInt(out, ids.size());
                        for (int id : ids) BufUtil.writeVarInt(out, id);
                        out.writeBytes(buf);
                        return Result.modify(BufUtil.readRemaining(out));
                    } finally { out.release(); }
                }
                case YsmConstants.S2C_HANDSHAKE -> {
                    handshaked = true;
                    return Result.pass(data);
                }
                default -> { return Result.pass(data); }
            }
        } catch (RuntimeException ex) {
            mapper.getLogger().warn("[Freesia] Invalid YSM S2C payload; dropped", ex);
            return Result.drop();
        } finally { buf.release(); }
    }

    public Result processC2S(byte[] data) {
        ByteBuf buf = Unpooled.wrappedBuffer(data);
        try {
            if (BufUtil.readVarInt(buf) == YsmConstants.C2S_HANDSHAKE) handshaked = true;
            return Result.pass(data);
        } catch (RuntimeException ex) {
            mapper.getLogger().warn("[Freesia] Invalid YSM C2S payload; dropped", ex);
            return Result.drop();
        } finally { buf.release(); }
    }
    public Player getPlayer() { return player; }
    public boolean hasHandshaked() { return handshaked; }
}
