package com.nguyendevs.freesia.neoforge.mapper;

import com.nguyendevs.freesia.neoforge.YsmConstants;
import com.nguyendevs.freesia.neoforge.util.BufUtil;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.slf4j.Logger;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Worker sessions survive backend switches. Model identity is UUID, never a backend entity ID. */
public class MapperManager {
    private final ProxyServer proxy;
    private final String workerHost;
    private final int workerPort;
    private final Logger logger;
    private final boolean debug;
    private final Map<UUID, MapperSession> sessions = new ConcurrentHashMap<>();
    private final Map<Integer, UUID> workerOwners = new HashMap<>();
    private final Map<UUID, byte[]> models = new HashMap<>();
    private final Map<UUID, View> views = new HashMap<>();
    private long nextTrackingRevision;

    /** A token for one client's current world; delayed work must retain this exact token. */
    public static final class View {
        private final Player player;
        private final Map<UUID, Integer> entities = new HashMap<>();
        private final Map<UUID, Long> trackingRevisions = new HashMap<>();
        private boolean ready;
        private View(Player player) { this.player = player; }
    }

    public MapperManager(ProxyServer proxy, String workerHost, int workerPort, Logger logger, boolean debug) {
        this.proxy = proxy;
        this.workerHost = workerHost;
        this.workerPort = workerPort;
        this.logger = logger;
        this.debug = debug;
    }

    public MapperSession createSession(Player player) {
        MapperSession existing = sessions.get(player.getUniqueId());
        if (existing != null) return existing;
        MapperSession session = new MapperSession(player, this, workerHost, workerPort);
        MapperSession raced = sessions.putIfAbsent(player.getUniqueId(), session);
        if (raced != null) return raced;
        try {
            session.connect();
        } catch (RuntimeException | LinkageError ex) {
            sessions.remove(player.getUniqueId(), session);
            logger.error("[Freesia] Worker connection initialization failed for " + player.getUsername(), ex);
            session.disconnect();
            throw ex;
        }
        return session;
    }

    public MapperSession getSession(UUID uuid) { return sessions.get(uuid); }

    public synchronized void onPlayerDisconnect(Player player) {
        View view = views.get(player.getUniqueId());
        if (view != null && view.player != player) return;
        MapperSession session = sessions.get(player.getUniqueId());
        if (session != null && session.getPlayer() != player) return;
        views.remove(player.getUniqueId());
        models.remove(player.getUniqueId());
        for (View other : views.values()) {
            other.entities.remove(player.getUniqueId());
            other.trackingRevisions.remove(player.getUniqueId());
        }
        if (session != null && sessions.remove(player.getUniqueId(), session)) {
            workerOwners.remove(session.getWorkerEntityId(), player.getUniqueId());
            session.disconnect();
        }
    }

    public synchronized void removeSession(MapperSession session) {
        // A late callback from an old connection must not remove its replacement.
        if (sessions.remove(session.getPlayer().getUniqueId(), session)) {
            workerOwners.remove(session.getWorkerEntityId(), session.getPlayer().getUniqueId());
            logger.warn("[Freesia] Worker session disconnected for {}", session.getPlayer().getUsername());
        }
    }

    public synchronized void onWorkerEntityId(MapperSession session, int workerId) {
        session.setWorkerEntityId(workerId);
        workerOwners.put(workerId, session.getPlayer().getUniqueId());
    }

    public synchronized View beginWorld(Player player, int selfId) {
        View view = new View(player);
        if (selfId >= 0) view.entities.put(player.getUniqueId(), selfId);
        views.put(player.getUniqueId(), view);
        if (debug) logger.info("[Freesia] New client world for {}, self entity {}", player.getUsername(), selfId);
        return view;
    }

    public synchronized int selfEntityId(Player player) {
        View view = views.get(player.getUniqueId());
        return view == null ? -1 : view.entities.getOrDefault(player.getUniqueId(), -1);
    }

    public synchronized View currentView(Player player) { return views.get(player.getUniqueId()); }

    public synchronized void markReady(View view) {
        if (isCurrent(view)) view.ready = true;
    }

    public synchronized boolean isCurrent(View view) {
        return view != null && views.get(view.player.getUniqueId()) == view && view.player.isActive();
    }

    public synchronized View track(Player viewer, UUID owner, int entityId) {
        View view = views.get(viewer.getUniqueId());
        return track(view, owner, entityId) ? view : null;
    }

    public synchronized boolean track(View view, UUID owner, int entityId) {
        return trackForReplay(view, owner, entityId) >= 0;
    }

    public synchronized long trackForReplay(View view, UUID owner, int entityId) {
        if (!isCurrent(view)) return -1;
        view.entities.entrySet().removeIf(e -> e.getValue() == entityId && !e.getKey().equals(owner));
        view.trackingRevisions.keySet().retainAll(view.entities.keySet());
        view.entities.put(owner, entityId);
        long revision = ++nextTrackingRevision;
        view.trackingRevisions.put(owner, revision);
        if (debug) logger.info("[Freesia] Track {} -> {} entity={} revision={}",
                owner, view.player.getUsername(), entityId, revision);
        return revision;
    }

    public synchronized void untrack(Player viewer, int[] ids) {
        untrack(views.get(viewer.getUniqueId()), ids);
    }

    public synchronized void untrack(View view, int[] ids) {
        if (isCurrent(view)) {
            for (int id : ids) view.entities.entrySet().removeIf(e -> e.getValue() == id);
            view.trackingRevisions.keySet().retainAll(view.entities.keySet());
        }
    }

    private boolean sameServer(Player viewer, UUID owner) {
        if (viewer.getUniqueId().equals(owner)) return true;
        Player subject = proxy.getPlayer(owner).orElse(null);
        if (subject == null) return false;
        var viewerServer = viewer.getCurrentServer();
        var subjectServer = subject.getCurrentServer();
        return viewerServer.isPresent() && subjectServer.isPresent()
                && viewerServer.get().getServer().equals(subjectServer.get().getServer());
    }

    public synchronized Integer entityForWorker(Player viewer, int workerId) {
        UUID owner = workerOwners.get(workerId);
        View view = views.get(viewer.getUniqueId());
        if (owner == null || view == null || !view.ready || !sameServer(viewer, owner)) return null;
        return view.entities.get(owner);
    }

    /** Only SET_MODEL is durable. Keep the Worker form and rewrite at delivery time. */
    public synchronized void routeEntityPacket(Player receiver, int packetId, int workerId, byte[] raw) {
        UUID owner = workerOwners.get(workerId);
        if (owner == null) {
            if (debug) logger.info("[Freesia] Dropped unmapped Worker entity {} packet {}", workerId, packetId);
            return;
        }
        boolean changedModel = false;
        if (packetId == YsmConstants.S2C_SET_MODEL) {
            changedModel = !Arrays.equals(models.put(owner, raw.clone()), raw);
        }
        // The first/latest state can arrive on ANY fake client's Worker connection.
        // Wake all visible recipients when it changes, but avoid N-squared duplicate broadcasts.
        if (changedModel || (packetId != YsmConstants.S2C_SET_MODEL && owner.equals(receiver.getUniqueId()))) {
            for (View view : views.values()) send(view, owner, raw);
        } else {
            send(views.get(receiver.getUniqueId()), owner, raw);
        }
    }

    private boolean send(View view, UUID owner, byte[] raw) {
        if (!isCurrent(view) || !view.ready || !sameServer(view.player, owner)) return false;
        Integer id = view.entities.get(owner);
        if (id == null) return false;
        return view.player.sendPluginMessage(YsmConstants.CHANNEL_KEY, rewriteEntity(raw, id));
    }

    public synchronized void replay(View view) {
        if (!isCurrent(view) || !view.ready) return;
        int count = 0;
        for (var entry : models.entrySet()) if (send(view, entry.getKey(), entry.getValue())) count++;
        if (debug) logger.info("[Freesia] Restored {} model(s) for {}", count, view.player.getUsername());
    }

    public synchronized void replayOne(View view, UUID owner) {
        byte[] raw = models.get(owner);
        if (raw != null) send(view, owner, raw);
    }

    public synchronized void replayTracked(View view, UUID owner, long revision) {
        if (!isCurrent(view) || !Objects.equals(view.trackingRevisions.get(owner), revision)) return;
        byte[] raw = models.get(owner);
        boolean sent = raw != null && send(view, owner, raw);
        if (debug) logger.info("[Freesia] Retrack model {} -> {} revision={} cached={} sent={}",
                owner, view.player.getUsername(), revision, raw != null, sent);
    }

    public static byte[] rewriteEntity(byte[] raw, int entityId) {
        ByteBuf in = Unpooled.wrappedBuffer(raw);
        ByteBuf out = Unpooled.buffer(raw.length + 5);
        try {
            BufUtil.writeVarInt(out, BufUtil.readVarInt(in));
            BufUtil.readVarInt(in);
            BufUtil.writeVarInt(out, entityId);
            out.writeBytes(in);
            return BufUtil.readRemaining(out);
        } finally { in.release(); out.release(); }
    }

    public boolean isDebug() { return debug; }
    public Logger getLogger() { return logger; }
}
