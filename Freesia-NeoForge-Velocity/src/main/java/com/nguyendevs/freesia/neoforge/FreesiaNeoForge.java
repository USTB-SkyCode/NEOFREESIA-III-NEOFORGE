package com.nguyendevs.freesia.neoforge;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.nguyendevs.freesia.neoforge.util.EntityPacketReader;
import io.netty.buffer.ByteBuf;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.google.inject.Inject;
import com.nguyendevs.freesia.neoforge.mapper.MapperManager;
import com.nguyendevs.freesia.neoforge.mapper.MapperSession;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

/**
 * Freesia II-like YSM proxy for NeoForge 1.21.1.
 * <p>
 * Intercepts {@code yes_steve_model:2.6.0} packets and routes them through a dedicated Worker
 * node (a NeoForge 1.21.1 server running YSM 2.6.5) instead of the backend, translating entity
 * ids between the Worker and the backend.
 */
public class FreesiaNeoForge implements PacketListener {

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    private FreesiaConfig config;
    private MapperManager mapperManager;

    @Inject
    public FreesiaNeoForge(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        try {
            FreesiaConfig.saveDefault(this.dataDirectory);
            this.config = FreesiaConfig.load(this.dataDirectory);
        } catch (IOException e) {
            this.logger.error("[Freesia] Failed to read config, using defaults.", e);
            this.config = new FreesiaConfig();
        }

        this.mapperManager = new MapperManager(
                this.server, this.config.getWorkerHost(), this.config.getWorkerPort(), this.logger, this.config.isDebug());

        this.server.getChannelRegistrar().register(YsmConstants.CHANNEL_KEY);

        PacketEvents.getAPI().getEventManager().registerListener(this, PacketListenerPriority.HIGHEST);

        this.logger.info("[Freesia] Enabled. Worker = {}:{}", this.config.getWorkerHost(), this.config.getWorkerPort());
    }

    /** Creates the Worker connection asynchronously once the player reaches a backend. */
    @Subscribe
    public EventTask onServerConnected(ServerConnectedEvent event) {
        final Player player = event.getPlayer();
        this.logger.info("[Freesia-DIAG] BACKEND_CONNECTED player={} server={}",
                player.getUsername(), event.getServer().getServerInfo().getName());
        return EventTask.async(() -> {
            MapperSession session = this.mapperManager.createSession(player);
            for (long delay : new long[]{10, 30}) {
                this.server.getScheduler().buildTask(this, session::reportStatus)
                        .delay(delay, TimeUnit.SECONDS).schedule();
            }
            this.logger.info("[Freesia] Mapper session allocated/reused for {}; Worker login status is logged separately",
                    player.getUsername());
        });
    }

    @Subscribe
    public void onChannelMsg(PluginMessageEvent event) {
        final ChannelIdentifier identifier = event.getIdentifier();
        if (!(identifier instanceof MinecraftChannelIdentifier mineId)) {
            return;
        }
        if (!mineId.getId().equals(YsmConstants.CHANNEL)) {
            return;
        }

        // The Worker owns YSM, not the backend. Consume both directions.
        event.setResult(PluginMessageEvent.ForwardResult.handled());

        if (event.getSource() instanceof Player player) {
            final MapperSession session = this.mapperManager.getSession(player.getUniqueId());
            if (session == null) {
                this.logger.warn("[Freesia] No mapper session for {} yet, dropping YSM packet",
                        player.getUsername());
                return;
            }
            session.relayC2S(event.getData());
        }
        // ServerConnection (backend -> client): dropped — the backend must not emit YSM.
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (this.mapperManager == null || event.isCancelled()) return;
        final Player target = event.getPlayer();
        if (target == null) return;
        try {
        if (event.getPacketType() == PacketType.Play.Server.JOIN_GAME) {
            int entityId = EntityPacketReader.join((ByteBuf) event.getByteBuf());
            worldReadyAfterSend(event, this.mapperManager.beginWorld(target, entityId));
        } else if (event.getPacketType() == PacketType.Play.Server.RESPAWN) {
            worldReadyAfterSend(event, this.mapperManager.beginWorld(target, this.mapperManager.selfEntityId(target)));
        } else if (event.getPacketType() == PacketType.Play.Server.CONFIGURATION_START) {
            // Invalidate every old-world task before configuration starts.
            this.mapperManager.beginWorld(target, -1);
        } else if (event.getPacketType() == PacketType.Play.Server.SPAWN_ENTITY
                || event.getPacketType() == PacketType.Play.Server.SPAWN_PLAYER) {
            // Modded entity type IDs are not necessarily present in PacketEvents' vanilla table.
            // The UUID of an online proxy player identifies our subject without decoding that table.
            var spawn = EntityPacketReader.spawn((ByteBuf) event.getByteBuf());
            if (this.server.getPlayer(spawn.uuid()).isPresent()) {
                trackAfterSend(event, target, spawn.uuid(), spawn.entityId());
            }
        } else if (event.getPacketType() == PacketType.Play.Server.DESTROY_ENTITIES) {
            final var view = this.mapperManager.currentView(target);
            final int[] ids = EntityPacketReader.destroyed((ByteBuf) event.getByteBuf());
            event.getPostTasks().add(() -> {
                if (!event.isCancelled()) this.mapperManager.untrack(view, ids);
            });
        }
        } catch (RuntimeException ex) {
            // Observation failure must never cancel or replace a backend's packet.
            this.logger.warn("[Freesia] Could not observe entity packet {}; leaving original bytes unchanged",
                    event.getPacketType(), ex);
        }
    }

    private void worldReadyAfterSend(PacketSendEvent event, MapperManager.View view) {
        // Velocity's PacketEvents 2.8 encoder executes postTasks, not tasksAfterSend.
        final var user = event.getUser();
        event.getPostTasks().add(() -> {
            if (event.isCancelled()) return;
            // Bounded retries cover client world initialization; each reads the newest model.
            for (long delay : new long[]{100, 500, 1500, 3000}) {
                this.server.getScheduler().buildTask(this, () -> {
                    if (user.getEncoderState() != ConnectionState.PLAY) return;
                    this.mapperManager.markReady(view);
                    this.mapperManager.replay(view);
                })
                        .delay(delay, TimeUnit.MILLISECONDS).schedule();
            }
        });
    }

    private void trackAfterSend(PacketSendEvent event, Player viewer, UUID owner, int entityId) {
        MapperManager.View view = this.mapperManager.currentView(viewer);
        event.getPostTasks().add(() -> {
            if (event.isCancelled()) return;
            long revision = this.mapperManager.trackForReplay(view, owner, entityId);
            if (revision < 0) return;
            for (long delay : new long[]{50, 250, 1000, 3000}) {
                this.server.getScheduler().buildTask(this, () -> this.mapperManager.replayTracked(view, owner, revision))
                        .delay(delay, TimeUnit.MILLISECONDS).schedule();
            }
        });
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        this.mapperManager.onPlayerDisconnect(event.getPlayer());
    }
}
