package io.moli.android;

import android.content.Context;
import android.net.*;
import android.net.nsd.*;
import android.net.wifi.WifiManager;
import android.os.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit Wi-Fi-only discovery. No persisted inventory, credentials, commands or background work. */
final class LocalDiscovery implements DiscoverySearch {
    static final int DURATION_MS=20000;
    private final Context app;
    private final boolean probes;
    private final boolean identityOnly;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newFixedThreadPool(8);
    private final ExecutorService announcements=Executors.newSingleThreadExecutor();
    private final Set<Closeable> sockets=ConcurrentHashMap.newKeySet();
    private final ArrayList<NsdManager.DiscoveryListener> nsdListeners=new ArrayList<>();
    private final ArrayDeque<NsdServiceInfo> resolveQueue=new ArrayDeque<>();
    private final Set<String> seenServices=new HashSet<>(), seenDescriptions=ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed=new AtomicBoolean();
    private Listener listener;
    private Network network;
    private NetworkEvidence.Scope scope;
    private ConnectivityManager cm;
    private NsdManager nsd;
    private WifiManager.MulticastLock multicast;
    private ConnectivityManager.NetworkCallback callback;
    private boolean resolving, registered;
    private NsdManager.ResolveListener resolver;
    private long deadline;
    private final Runnable expiry=() -> finish(End.COMPLETE);
    LocalDiscovery(Context context,boolean probes) {this(context,probes,false);}
    LocalDiscovery(Context context,boolean probes,boolean identityOnly) { app=context.getApplicationContext(); this.probes=probes; this.identityOnly=identityOnly; }
    @Override public void start(Listener l) {
        listener=l;
        try {
            cm=app.getSystemService(ConnectivityManager.class); network=cm.getActiveNetwork();
            NetworkCapabilities capabilities=cm.getNetworkCapabilities(network);
            if(capabilities==null || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) throw new IOException();
            scope=scope(cm.getLinkProperties(network));
            nsd=app.getSystemService(NsdManager.class);
            WifiManager wifi=(WifiManager)app.getSystemService(Context.WIFI_SERVICE);
            multicast=wifi.createMulticastLock("moli-manual-discovery"); multicast.setReferenceCounted(false); multicast.acquire();
            callback=new ConnectivityManager.NetworkCallback() {
                @Override public void onLost(Network n) { if(n.equals(network)) main.post(() -> finish(End.NETWORK_CHANGED)); }
                @Override public void onAvailable(Network n) { if(!n.equals(network)) main.post(() -> finish(End.NETWORK_CHANGED)); }
                @Override public void onCapabilitiesChanged(Network n,NetworkCapabilities c) {
                    if(n.equals(network) && (!c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_VPN))) main.post(() -> finish(End.NETWORK_CHANGED));
                }
                @Override public void onLinkPropertiesChanged(Network n,LinkProperties properties) {
                    if(!n.equals(network)) return;
                    try { NetworkEvidence.Scope s=scope(properties); if(s.own!=scope.own || s.prefix!=scope.prefix) main.post(() -> finish(End.NETWORK_CHANGED)); }
                    catch(Exception e) { main.post(() -> finish(End.NETWORK_CHANGED)); }
                }
            };
            cm.registerDefaultNetworkCallback(callback); registered=true;
            deadline=SystemClock.elapsedRealtime()+DURATION_MS; main.postDelayed(expiry,DURATION_MS);
            discover("_ipp._tcp."); discover("_http._tcp."); discover("_googlecast._tcp.");
            announcements.execute(() -> ssdp(new InetSocketAddress("239.255.255.250",1900)));
            if(probes) {
                List<String> hosts=scope.probeHosts();
                if(hosts.isEmpty()) listener.warning();
                for(String host:hosts) for(int port:identityOnly?new int[]{80}:new int[]{80,631}) io.execute(() -> probe(host,port));
            }
        } catch(Exception failure) { finish(End.UNAVAILABLE); }
    }
    static NetworkEvidence.Scope scope(LinkProperties properties) throws IOException {
        if(properties!=null) for(LinkAddress address:properties.getLinkAddresses()) {
            if(address.getAddress() instanceof Inet4Address && NetworkEvidence.privateIp(NetworkEvidence.ipv4(address.getAddress().getHostAddress())))
                return new NetworkEvidence.Scope(address.getAddress().getHostAddress(),address.getPrefixLength());
        }
        throw new IOException("Wi-Fi IPv4 unavailable");
    }
    private boolean active() { return !closed.get() && SystemClock.elapsedRealtime()<deadline; }
    private void emit(NetworkEvidence.Entry e) { if(active() && scope.contains(e.ip)) listener.found(e); }
    private void warning() { if(active()) listener.warning(); }
    private void discover(String type) {
        NsdManager.DiscoveryListener discovery=new NsdManager.DiscoveryListener() {
            public void onDiscoveryStarted(String t) {}
            public void onDiscoveryStopped(String t) {}
            public void onStartDiscoveryFailed(String t,int error) { main.post(() -> { if(active()) warning(); }); }
            public void onStopDiscoveryFailed(String t,int error) {}
            public void onServiceLost(NsdServiceInfo info) {}
            public void onServiceFound(NsdServiceInfo info) { main.post(() -> {
                if(!active() || seenServices.size()>=128 || !(type.equals(info.getServiceType()) || type.equals(info.getServiceType()+"."))) return;
                String name=NetworkEvidence.safeName(info.getServiceName());
                if(name.isEmpty() || !seenServices.add(type+name)) return;
                resolveQueue.add(info); resolveNext();
            }); }
        };
        nsdListeners.add(discovery);
        try {
            if(Build.VERSION.SDK_INT>=33) nsd.discoverServices(type,NsdManager.PROTOCOL_DNS_SD,network,app.getMainExecutor(),discovery);
            else nsd.discoverServices(type,NsdManager.PROTOCOL_DNS_SD,discovery);
        } catch(Exception e) { warning(); }
    }
    @SuppressWarnings("deprecation") // API 28 is required by the target tablet.
    private void resolveNext() {
        if(!active() || resolving || resolveQueue.isEmpty()) return;
        NsdServiceInfo next=resolveQueue.remove(); resolving=true;
        resolver=new NsdManager.ResolveListener() {
            public void onResolveFailed(NsdServiceInfo info,int error) { main.post(() -> { resolving=false; resolveNext(); }); }
            public void onServiceResolved(NsdServiceInfo info) { main.post(() -> {
                resolving=false;
                InetAddress address=info.getHost();
                if(active() && address instanceof Inet4Address && info.getPort()>0 && info.getPort()<=65535) {
                    NetworkEvidence.Entry entry=NetworkEvidence.mdns(address.getHostAddress(),info.getPort(),info.getServiceName(),info.getServiceType(),info.getAttributes().get("rp"));
                    if(entry!=null) emit(entry);
                }
                resolveNext();
            }); }
        };
        try { nsd.resolveService(next,resolver); } catch(Exception failure) { resolving=false; warning(); resolveNext(); }
    }
    // Package-visible transport seam for fixtures in a disposable emulator; production target is fixed above.
    void ssdp(InetSocketAddress target) {
        try(DatagramSocket socket=new DatagramSocket(null)) {
            sockets.add(socket); network.bindSocket(socket); socket.bind(new InetSocketAddress(0)); socket.setSoTimeout(350);
            byte[] search=("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: ssdp:all\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
            if(!active()) return;
            socket.send(new DatagramPacket(search,search.length,target));
            long until=Math.min(deadline,SystemClock.elapsedRealtime()+7000);
            byte[] bytes=new byte[4097]; int packets=0;
            while(active() && SystemClock.elapsedRealtime()<until && packets<128) {
                DatagramPacket packet=new DatagramPacket(bytes,bytes.length);
                try { socket.receive(packet); } catch(SocketTimeoutException timeout) { continue; }
                packets++;
                String source=packet.getAddress().getHostAddress();
                if(packet.getLength()>4096 || !scope.contains(source)) continue;
                String raw=new String(bytes,0,packet.getLength(),StandardCharsets.UTF_8);
                try {
                    JSONObject input=new JSONObject().put("source",source).put("packet",raw);
                    JSONObject fact=ProfileStore.nativeResult(input.toString(),5);
                    String location=fact.getString("location");
                    emit(entry(fact,NetworkEvidence.Kind.UPNP,NetworkEvidence.Proof.ANNOUNCEMENT));
                    if(new URL(location).getPath().toLowerCase(Locale.ROOT).endsWith(".xml") && seenDescriptions.size()<32 && seenDescriptions.add(location)) io.execute(() -> describe(input,location));
                } catch(Exception malformed) { /* Untrusted LAN data is ignored, never logged. */ }
            }
        } catch(Exception failure) { warning(); }
    }
    private static NetworkEvidence.Entry entry(JSONObject fact,NetworkEvidence.Kind kind,NetworkEvidence.Proof proof) throws JSONException {
        return new NetworkEvidence.Entry(fact.getString("host"),fact.getInt("port"),fact.optString("name"),kind,proof,"",fact.getString("location"));
    }
    private void describe(JSONObject input,String location) {
        if(!active()) return;
        try(Socket socket=new Socket()) {
            sockets.add(socket); network.bindSocket(socket); socket.setSoTimeout(1000);
            URL url=new URL(location);
            if(!active()) return;
            socket.connect(new InetSocketAddress(url.getHost(),url.getPort()==-1?80:url.getPort()),1000);
            String description=DiscoveryHttp.description(socket,url.getHost(),url.getPort()==-1?80:url.getPort(),url.getPath());
            JSONObject fact=ProfileStore.nativeResult(input.put("description",description).toString(),5);
            emit(entry(fact,fact.optBoolean("igd")?NetworkEvidence.Kind.IGD:NetworkEvidence.Kind.UPNP,NetworkEvidence.Proof.DESCRIPTION));
        } catch(Exception unavailable) { /* The unverified announcement remains visible. */ }
    }

    private void probe(String host,int port) {
        if(!active()) return;
        try(Socket socket=new Socket()) {
            sockets.add(socket); network.bindSocket(socket);
            if(!active()) return;
            socket.connect(new InetSocketAddress(host,port),200);
            emit(new NetworkEvidence.Entry(host,port,"",port==631?NetworkEvidence.Kind.IPP:NetworkEvidence.Kind.HTTP,NetworkEvidence.Proof.OPEN_PORT,"",""));
        } catch(Exception unavailable) { /* A silent host is not proof that it is absent. */ }
    }
    private void finish(End reason) {
        if(!closed.compareAndSet(false,true)) return;
        main.removeCallbacks(expiry);
        for(Closeable socket:sockets) try { socket.close(); } catch(IOException ignored) {}
        sockets.clear(); io.shutdownNow(); announcements.shutdownNow();
        if(registered) try { cm.unregisterNetworkCallback(callback); } catch(Exception ignored) {}
        for(NsdManager.DiscoveryListener l:nsdListeners) try { nsd.stopServiceDiscovery(l); } catch(Exception ignored) {}
        if(Build.VERSION.SDK_INT>=34 && resolving && resolver!=null) try { nsd.stopServiceResolution(resolver); } catch(Exception ignored) {}
        nsdListeners.clear(); resolveQueue.clear();
        if(multicast!=null && multicast.isHeld()) multicast.release();
        if(listener!=null) listener.finished(reason);
    }
    @Override public void close() { finish(End.CANCELLED); }
}
