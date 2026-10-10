package com.nguyendevs.freesia.neoforge.mapper;

import com.nguyendevs.freesia.neoforge.YsmConstants;
import com.nguyendevs.freesia.neoforge.proxy.YsmPacketProxy;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.key.Key;
import org.geysermc.mcprotocollib.auth.GameProfile;
import org.geysermc.mcprotocollib.network.BuiltinFlags;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.ConnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.DisconnectingEvent;
import org.geysermc.mcprotocollib.network.event.session.PacketErrorEvent;
import org.geysermc.mcprotocollib.network.event.session.PacketSendingEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionListener;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.network.tcp.TcpClientSession;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundCustomPayloadPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundKeepAlivePacket;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundPingPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundCustomPayloadPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundKeepAlivePacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundPongPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import com.nguyendevs.freesia.neoforge.util.BufUtil;
import io.netty.buffer.Unpooled;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A fake vanilla client connection from the proxy to the Worker node (NeoForge 1.21.1 + YSM).
 * <p>
 * The Worker believes it is talking to a real player, so its YSM model-sync logic (server-side
 * Netty + connection-handler checks) works. This session relays YSM packets to/from the real
 * player, translating entity ids along the way.
 */
public class MapperSession implements SessionListener {

    private static final Key YSM_KEY = Key.key(YsmConstants.CHANNEL);

    private final Player player;
    private final MapperManager manager;
    private final YsmPacketProxy proxy;
    private final String host;
    private final int port;

    private volatile TcpClientSession session;
    private volatile int workerEntityId = -1;
    private volatile int backendEntityId = -1;
    private final long started = System.nanoTime();
    private volatile boolean tcpConnected, closed, s2cHandshake, c2sHandshake;
    private volatile String lastReceived = "none", lastSent = "none";
    private final Set<String> stages = ConcurrentHashMap.newKeySet();
    private final AtomicInteger receivedYsm = new AtomicInteger(), sentYsm = new AtomicInteger();

    private void stage(String message) {
        if (stages.add(message)) this.manager.getLogger().info("[Freesia-DIAG] player={} {}",
                this.player.getUsername(), message);
    }

    public void reportStatus() {
        if (closed || !player.isActive()) return;
        this.manager.getLogger().info("[Freesia-DIAG] STATUS {}", diagnosticStatus());
    }

    public String diagnosticStatus() {
        String waiting = !tcpConnected ? "TCP_CONNECT" : workerEntityId < 0 ? "MINECRAFT_LOGIN_OR_CONFIGURATION"
                : !s2cHandshake ? "WORKER_YSM_HANDSHAKE" : !c2sHandshake ? "CLIENT_YSM_REPLY" : "YSM_HANDSHAKE_OBSERVED";
        return "player=" + player.getUsername() + " endpoint=" + host + ":" + port
                + " elapsedMs=" + (System.nanoTime()-started)/1_000_000 + " closed=" + closed
                + " stage=" + waiting + " lastRx=" + lastReceived + " lastTx=" + lastSent
                + " ysmRx=" + receivedYsm.get() + " ysmTxConfirmed=" + sentYsm.get();
    }

    private int packetId(byte[] data) {
        var buf = Unpooled.wrappedBuffer(data);
        try { return BufUtil.readVarInt(buf); }
        catch (RuntimeException ex) { return -1; }
        finally { buf.release(); }
    }

    public MapperSession(Player player, MapperManager manager, String host, int port) {
        this.player = player;
        this.manager = manager;
        this.proxy = new YsmPacketProxy(player, manager);
        this.host = host;
        this.port = port;
    }

    public void connect() {
        this.manager.getLogger().info("[Freesia] Worker CONNECTING player={} endpoint={}:{}",
                this.player.getUsername(), this.host, this.port);
        final MinecraftProtocol protocol = new MinecraftProtocol(
                new GameProfile(this.player.getUniqueId(), this.player.getUsername()), null);
        this.session = new TcpClientSession(this.host, this.port, protocol);
        this.session.addListener(this);
        this.session.setFlag(BuiltinFlags.READ_TIMEOUT, 30_000);
        this.session.setFlag(BuiltinFlags.WRITE_TIMEOUT, 30_000);
        this.session.connect(true, false);
    }

    @Override
    public void packetReceived(Session session, Packet packet) {
        lastReceived = packet.getClass().getSimpleName();
        if (packet.getClass().getPackageName().contains(".login.")
                || packet.getClass().getPackageName().contains(".configuration.")) {
            stage("WORKER_RX " + lastReceived);
        }
        if (packet instanceof ClientboundLoginPacket loginPacket) {
            this.workerEntityId = loginPacket.getEntityId();
            this.manager.onWorkerEntityId(this, this.workerEntityId);
            this.manager.getLogger().info("[Freesia] Worker PLAY_READY player={} entity={}",
                    this.player.getUsername(), this.workerEntityId);
            return;
        }

        // Keep the fake client's connection alive (PLAY-phase keep-alive + CONFIG-phase ping).
        if (packet instanceof ClientboundKeepAlivePacket keepAlive) {
            session.send(new ServerboundKeepAlivePacket(keepAlive.getPingId()));
            return;
        }

        if (packet instanceof ClientboundPingPacket ping) {
            session.send(new ServerboundPongPacket(ping.getId()));
            return;
        }

        if (packet instanceof ClientboundCustomPayloadPacket payloadPacket) {
            final String channel = payloadPacket.getChannel().asString();
            stage("WORKER_CHANNEL " + channel);
            if (channel.equals(YsmConstants.CHANNEL)) {
                final byte[] data = payloadPacket.getData();
                receivedYsm.incrementAndGet();
                int id = packetId(data);
                if (id == YsmConstants.S2C_HANDSHAKE) s2cHandshake = true;
                stage("YSM_WORKER_RX packetId=" + id);
                final YsmPacketProxy.Result result = this.proxy.processS2C(data);
                switch (result.kind()) {
                    case MODIFY -> {
                        boolean sent = this.player.sendPluginMessage(YsmConstants.CHANNEL_KEY, result.data());
                        stage("YSM_CLIENT_SEND packetId=" + id + " accepted=" + sent);
                    }
                    case PASS -> {
                        boolean sent = this.player.sendPluginMessage(YsmConstants.CHANNEL_KEY, data);
                        stage("YSM_CLIENT_SEND packetId=" + id + " accepted=" + sent);
                    }
                    case DROP -> {
                        stage("YSM_ROUTER_CONSUMED packetId=" + id + " (entity packets routed separately)");
                    }
                }
            }
        }
    }

    @Override
    public void packetSending(PacketSendingEvent event) {
    }

    @Override
    public void packetSent(Session session, Packet packet) {
        lastSent = packet.getClass().getSimpleName();
        if (packet.getClass().getPackageName().contains(".login.")
                || packet.getClass().getPackageName().contains(".configuration.")) stage("WORKER_TX " + lastSent);
        if (packet instanceof ServerboundCustomPayloadPacket payload && payload.getChannel().equals(YSM_KEY)) {
            sentYsm.incrementAndGet();
            stage("YSM_WORKER_TX_CONFIRMED packetId=" + packetId(payload.getData()));
        }
    }

    @Override
    public void packetError(PacketErrorEvent event) {
        this.manager.getLogger().error("[Freesia-DIAG] {}", diagnosticStatus());
        this.manager.getLogger().error("[Freesia] Worker PACKET_ERROR player=" + this.player.getUsername(), event.getCause());
    }

    @Override
    public void connected(ConnectedEvent event) {
        tcpConnected = true;
        this.manager.getLogger().info("[Freesia] Worker TCP_CONNECTED player={} (Minecraft/YSM login not yet confirmed)",
                this.player.getUsername());
    }

    @Override
    public void disconnecting(DisconnectingEvent event) {
    }

    @Override
    public void disconnected(DisconnectedEvent event) {
        closed = true;
        this.manager.getLogger().warn("[Freesia-DIAG] {}", diagnosticStatus());
        this.manager.getLogger().warn("[Freesia] Worker DISCONNECTED player={} endpoint={}:{} reason={}",
                this.player.getUsername(), this.host, this.port, event.getReason());
        if (event.getCause() != null) this.manager.getLogger().error("[Freesia] Worker disconnect cause", event.getCause());
        this.manager.removeSession(this);
    }

    /** Relays a client -> Worker YSM packet. */
    public void relayC2S(byte[] data) {
        int id = packetId(data);
        if (id == YsmConstants.C2S_HANDSHAKE) c2sHandshake = true;
        stage("YSM_CLIENT_RX packetId=" + id);
        final YsmPacketProxy.Result result = this.proxy.processC2S(data);
        final TcpClientSession s = this.session;
        if (s == null || !s.isConnected()) {
            this.manager.getLogger().warn("[Freesia-DIAG] YSM_C2S_NOT_SENT packetId={} {}", id, diagnosticStatus());
            return;
        }
        switch (result.kind()) {
            case MODIFY -> s.send(new ServerboundCustomPayloadPacket(YSM_KEY, result.data()));
            case PASS -> s.send(new ServerboundCustomPayloadPacket(YSM_KEY, data));
            case DROP -> {
            }
        }
    }

    public void disconnect() {
        final TcpClientSession s = this.session;
        if (s != null) {
            s.disconnect("mapper closed");
        }
    }

    public void setBackendEntityId(int id) {
        this.backendEntityId = id;
    }

    public void setWorkerEntityId(int id) {
        this.workerEntityId = id;
    }

    public int getBackendEntityId() {
        return this.backendEntityId;
    }

    public int getWorkerEntityId() {
        return this.workerEntityId;
    }

    public Player getPlayer() {
        return this.player;
    }
}
