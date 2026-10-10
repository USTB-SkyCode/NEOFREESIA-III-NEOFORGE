import com.nguyendevs.freesia.neoforge.mapper.*;
import com.nguyendevs.freesia.neoforge.proxy.YsmPacketProxy;
import com.nguyendevs.freesia.neoforge.util.BufUtil;
import com.velocitypowered.api.proxy.*;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import io.netty.buffer.*;
import org.slf4j.helpers.NOPLogger;
import java.lang.reflect.*;
import java.util.*;

/** Exercises the actual compiled manager and packet parser with in-memory Velocity interfaces. */
public class SwitchRegressionTest {
    static int passed;
    static Map<UUID, Person> people = new HashMap<>();
    @SuppressWarnings("unchecked")
    static <T> T fake(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class[]{type}, (p,m,a) -> {
            if (m.getName().equals("equals")) return p == a[0];
            if (m.getName().equals("hashCode")) return System.identityHashCode(p);
            if (m.getName().equals("toString")) return type.getSimpleName()+"Stub";
            return handler.invoke(p,m,a);
        });
    }
    static class Person {
        UUID id = UUID.randomUUID();
        RegisteredServer server;
        boolean active = true;
        List<byte[]> sent = new ArrayList<>();
        Player player = fake(Player.class, (p,m,a) -> switch(m.getName()) {
            case "getUniqueId" -> id;
            case "getUsername" -> id.toString();
            case "isActive" -> active;
            case "getCurrentServer" -> Optional.of(fake(ServerConnection.class,
                    (q,n,b) -> n.getName().equals("getServer") ? server : null));
            case "sendPluginMessage" -> { sent.add(((byte[])a[1]).clone()); yield true; }
            default -> null;
        });
        Person(RegisteredServer server) { this.server=server; people.put(id,this); }
    }
    static byte[] packet(int kind,int id,int marker) {
        ByteBuf buf=Unpooled.buffer();
        try { BufUtil.writeVarInt(buf,kind); BufUtil.writeVarInt(buf,id); buf.writeInt(marker); return BufUtil.readRemaining(buf); }
        finally {buf.release();}
    }
    static int[] decoded(byte[] data) {
        ByteBuf b=Unpooled.wrappedBuffer(data);
        try {return new int[]{BufUtil.readVarInt(b),BufUtil.readVarInt(b),b.readInt()};}
        finally {b.release();}
    }
    static void check(boolean ok,String description) {
        if(!ok) throw new AssertionError(description);
        passed++; System.out.println("PASS "+description);
    }
    static void last(Person p,int kind,int entity,int marker,String description) {
        check(!p.sent.isEmpty() && Arrays.equals(decoded(p.sent.getLast()),new int[]{kind,entity,marker}),description);
    }
    static MapperSession bind(MapperManager m,Person p,int worker) {
        MapperSession s=new MapperSession(p.player,m,"localhost",25566);
        m.onWorkerEntityId(s,worker); return s;
    }
    public static void main(String[] args) throws Exception {
        RegisteredServer a=fake(RegisteredServer.class,(p,m,v)->null);
        RegisteredServer b=fake(RegisteredServer.class,(p,m,v)->null);
        ProxyServer proxy=fake(ProxyServer.class,(p,m,v)->m.getName().equals("getPlayer")
                ? Optional.ofNullable(people.get(v[0])).map(x->x.player) : null);
        MapperManager m=new MapperManager(proxy,"localhost",25566,NOPLogger.NOP_LOGGER,false);
        Person self=new Person(a), peer=new Person(a), foreign=new Person(b);
        bind(m,self,10); bind(m,peer,11); bind(m,foreign,12);
        YsmPacketProxy parser=new YsmPacketProxy(self.player,m);
        var old=m.beginWorld(self.player,100);
        parser.processS2C(packet(4,10,777));
        check(self.sent.isEmpty(),"cache before world readiness, without sending prematurely");
        m.markReady(old); m.replay(old);
        last(self,4,100,777,"initial model replay");
        var pv=m.beginWorld(peer.player,101);m.markReady(pv);m.track(peer.player,self.id,100);
        var fv=m.beginWorld(foreign.player,100);m.markReady(fv);m.track(foreign.player,self.id,100);
        parser.processS2C(packet(4,10,888));
        last(peer,4,100,888,"model reaches tracked same-server viewer");
        check(foreign.sent.isEmpty(),"same integer ID on another server does not receive model");
        parser.processS2C(packet(21,10,123));
        self.sent.clear();peer.sent.clear();
        self.server=b;
        var switched=m.beginWorld(self.player,20000);m.markReady(switched);m.replay(switched);
        last(self,4,20000,888,"switch rewrites new VarInt ID and animation did not replace model");
        int count=self.sent.size();m.replay(old);
        check(self.sent.size()==count,"old-world delayed task is invalidated");
        check(!m.track(old,peer.id,666),"late spawn callback cannot mutate replacement world");
        m.replay(pv);
        check(peer.sent.isEmpty(),"stale tracking in previous server cannot leak model");
        m.track(foreign.player,self.id,4321);m.replayOne(fv,self.id);
        last(foreign,4,4321,888,"new viewer uses its own observed entity ID");
        foreign.sent.clear();m.untrack(foreign.player,new int[]{4321});m.replayOne(fv,self.id);
        check(foreign.sent.isEmpty(),"despawn cancels delayed replay");
        m.track(foreign.player,self.id,4321);m.track(foreign.player,peer.id,4321);m.replayOne(fv,self.id);
        check(foreign.sent.isEmpty(),"entity ID reuse removes previous UUID mapping");
        parser.processS2C(packet(4,10,999));m.replay(switched);
        last(self,4,20000,999,"retry reads latest selection instead of stale snapshot");
        count=self.sent.size();parser.processS2C(packet(4,99999,333));
        check(self.sent.size()==count,"unknown Worker entity is not passed to client");
        var config=m.beginWorld(self.player,-1);parser.processS2C(packet(4,10,444));
        check(self.sent.size()==count,"configuration phase caches but does not send model");
        var resumed=m.beginWorld(self.player,7);m.markReady(resumed);m.replay(resumed);
        last(self,4,7,444,"state received during configuration survives into new world");
        m.markReady(config);count=self.sent.size();m.replay(config);
        check(self.sent.size()==count,"late ready callback cannot resurrect old world");
        var respawn=m.beginWorld(self.player,m.selfEntityId(self.player));m.markReady(respawn);m.replay(respawn);
        last(self,4,7,444,"respawn restores same-ID player model");
        self.active=false;count=self.sent.size();m.replay(respawn);
        check(self.sent.size()==count,"offline client does not receive retries");self.active=true;
        m.onPlayerDisconnect(self.player);var fresh=m.beginWorld(self.player,9);m.markReady(fresh);self.sent.clear();m.replay(fresh);
        check(self.sent.isEmpty(),"disconnect clears previous player cache");
        check(parser.processS2C(new byte[]{(byte)128}).kind()==YsmPacketProxy.Kind.DROP,"malformed payload does not escape parser");
        check(parser.processC2S(new byte[]{5,1,2}).kind()==YsmPacketProxy.Kind.PASS,"model selection still forwards to Worker");
        byte[] large=packet(4,Integer.MAX_VALUE,123456);byte[] rewritten=MapperManager.rewriteEntity(large,1);
        check(Arrays.equals(decoded(rewritten),new int[]{4,1,123456}),"ID shrink preserves opaque body bytes");
        Person one=new Person(a),two=new Person(a);
        bind(m,one,71);bind(m,two,72);
        var ov=m.beginWorld(one.player,301);m.markReady(ov);m.track(one.player,two.id,302);
        var tv=m.beginWorld(two.player,400);m.markReady(tv);m.track(two.player,one.id,7777);
        YsmPacketProxy op=new YsmPacketProxy(one.player,m);
        byte[] original=packet(4,71,654321);op.processS2C(original);original[original.length-1]=0;
        m.replay(ov);last(one,4,301,654321,"cached model does not alias input byte array");
        last(two,4,7777,654321,"same-server recipients receive independently rewritten IDs");
        ByteBuf molang=Unpooled.buffer();
        try {
            BufUtil.writeVarInt(molang,3);BufUtil.writeVarInt(molang,3);
            BufUtil.writeVarInt(molang,71);BufUtil.writeVarInt(molang,72);BufUtil.writeVarInt(molang,9999);
            BufUtil.writeUtf(molang,"test.expression");
            var result=op.processS2C(BufUtil.readRemaining(molang));
            ByteBuf mapped=Unpooled.wrappedBuffer(result.data());
            try {
                check(BufUtil.readVarInt(mapped)==3 && BufUtil.readVarInt(mapped)==2
                      && BufUtil.readVarInt(mapped)==301 && BufUtil.readVarInt(mapped)==302
                      && BufUtil.readUtf(mapped).equals("test.expression"),"Molang filters unknown entities and preserves expression");
            } finally {mapped.release();}
        } finally {molang.release();}
        two.sent.clear();m.untrack(two.player,new int[]{7777});
        m.replayOne(tv,one.id);
        check(two.sent.isEmpty(),"leaving view suppresses model replay but retains owner's model");
        m.track(two.player,one.id,7777);m.replayOne(tv,one.id);
        last(two,4,7777,654321,"re-entering view restores model without selecting again");
        two.sent.clear();m.untrack(two.player,new int[]{7777});
        m.track(two.player,one.id,8888);m.replayOne(tv,one.id);
        last(two,4,8888,654321,"re-entering with changed ID restores cached model");
        Person late=new Person(a);bind(m,late,73);
        m.track(two.player,late.id,9090);two.sent.clear();m.replayOne(tv,late.id);
        check(two.sent.isEmpty(),"newly tracked player can wait for missing model cache");
        // Worker may deliver the first copy to a different fake client's connection.
        op.processS2C(packet(4,73,4545));
        last(two,4,9090,4545,"late model from another Worker session reaches all waiting viewers");
        long firstTrack=m.trackForReplay(tv,one.id,8888);
        two.sent.clear();m.untrack(two.player,new int[]{8888});
        m.replayTracked(tv,one.id,firstTrack);
        check(two.sent.isEmpty(),"tracking retry is cancelled on leaving view");
        long secondTrack=m.trackForReplay(tv,one.id,8888);
        m.replayTracked(tv,one.id,firstTrack);
        check(two.sent.isEmpty(),"same-ID re-entry does not revive previous tracking retry");
        m.replayTracked(tv,one.id,secondTrack);
        last(two,4,8888,654321,"new tracking generation restores model");
        for(int i=0;i<20;i++) {
            m.untrack(two.player,new int[]{8888+i});
            long token=m.trackForReplay(tv,one.id,8889+i);
            two.sent.clear();m.replayTracked(tv,one.id,token);
            if(two.sent.size()!=1 || decoded(two.sent.getFirst())[1]!=8889+i)
                throw new AssertionError("repeated leave/re-enter cycle "+i);
        }
        check(true,"20 repeated leave/re-enter cycles restore without model reselection");
        two.sent.clear();op.processS2C(packet(4,73,4545));
        check(two.sent.isEmpty(),"duplicate Worker copies do not rebroadcast to every viewer");
        op.processS2C(packet(4,73,5656));
        last(two,4,9090,5656,"updated model arriving via another connection reaches visible viewer");
        long beforeSwitch=m.trackForReplay(tv,one.id,12000);
        var switchedAgain=m.beginWorld(two.player,500);m.markReady(switchedAgain);two.sent.clear();
        m.replayTracked(tv,one.id,beforeSwitch);
        check(two.sent.isEmpty(),"world switch invalidates tracking retry");
        var diagSession=new MapperSession(one.player,m,"localhost",25566);
        check(diagSession.diagnosticStatus().contains("stage=TCP_CONNECT"),"diagnostics distinguish TCP not connected");
        diagSession.connected(new org.geysermc.mcprotocollib.network.event.session.ConnectedEvent(null));
        check(diagSession.diagnosticStatus().contains("stage=MINECRAFT_LOGIN_OR_CONFIGURATION"),"TCP connection is not reported as successful login");
        diagSession.setWorkerEntityId(71);
        check(diagSession.diagnosticStatus().contains("stage=WORKER_YSM_HANDSHAKE"),"diagnostics identify missing Worker YSM handshake");
        diagSession.packetReceived(null,new org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundCustomPayloadPacket(
            net.kyori.adventure.key.Key.key("yes_steve_model:2_6_0"),new byte[]{51}));
        check(diagSession.diagnosticStatus().contains("stage=CLIENT_YSM_REPLY"),"diagnostics identify missing client handshake reply");
        diagSession.relayC2S(new byte[]{52});
        check(diagSession.diagnosticStatus().contains("stage=YSM_HANDSHAKE_OBSERVED")
              && diagSession.diagnosticStatus().contains("ysmTxConfirmed=0"),"observed client reply is not counted as transmitted");
        diagSession.disconnected(new org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent(null,
                net.kyori.adventure.text.Component.text("Connection refused"),new java.net.ConnectException("refused")));
        check(diagSession.diagnosticStatus().contains("closed=true"),"diagnostics retain disconnected status");
        ByteBuf modSpawn=Unpooled.buffer();
        try {
            modSpawn.writeByte(99);int start=modSpawn.writerIndex();
            BufUtil.writeVarInt(modSpawn,54321);modSpawn.writeLong(one.id.getMostSignificantBits());modSpawn.writeLong(one.id.getLeastSignificantBits());
            BufUtil.writeVarInt(modSpawn,999999);modSpawn.writeInt(123);modSpawn.readerIndex(start);
            byte[] originalBytes=new byte[modSpawn.writerIndex()];modSpawn.getBytes(0,originalBytes);
            var spawn=com.nguyendevs.freesia.neoforge.util.EntityPacketReader.spawn(modSpawn);
            check(spawn.entityId()==54321 && spawn.uuid().equals(one.id),"modded unknown entity type does not require registry decoding");
            byte[] afterBytes=new byte[modSpawn.writerIndex()];modSpawn.getBytes(0,afterBytes);
            check(modSpawn.readerIndex()==start && Arrays.equals(originalBytes,afterBytes),"observing modded spawn preserves original bytes and reader position");
        } finally {modSpawn.release();}
        System.out.println("Regression checks passed: "+passed);
    }
}
