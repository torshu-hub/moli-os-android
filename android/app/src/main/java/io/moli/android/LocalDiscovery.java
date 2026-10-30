package io.moli.android;

import android.content.Context;
import android.net.*;
import android.net.nsd.*;
import android.net.wifi.WifiManager;
import android.os.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Code39 transport adapted to one selected Wi-Fi/Ethernet LAN. Manual and bounded. */
final class LocalDiscovery implements DiscoverySearch {
    static final int DURATION_MS=20000;
    private final Context app;
    private final Lan selected;
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
    static final class Lan {
        final Network network;final NetworkEvidence.Scope scope;final String iface;final boolean wifi;
        Lan(Network network,NetworkEvidence.Scope scope,String iface,boolean wifi){this.network=network;this.scope=scope;this.iface=iface;this.wifi=wifi;}
        String label(Context context){return context.getString(wifi?R.string.network_wifi:R.string.network_ethernet)+" · "+NetworkEvidence.address(scope.own)+"/"+scope.prefix;}
        boolean same(Lan other){return other!=null&&network.equals(other.network)&&scope.own==other.scope.own&&scope.prefix==other.scope.prefix&&iface.equals(other.iface)&&wifi==other.wifi;}
    }
    static boolean eligible(NetworkCapabilities c){
        return c!=null&&!c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)&&!c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            &&(c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)||c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
    }
    static Lan current(Context context)throws IOException {
        ConnectivityManager manager=context.getSystemService(ConnectivityManager.class);Network active=manager.getActiveNetwork();
        NetworkCapabilities capabilities=manager.getNetworkCapabilities(active);
        if(!eligible(capabilities))throw new IOException("network");
        LinkProperties properties=manager.getLinkProperties(active);
        if(properties==null||properties.getInterfaceName()==null)throw new IOException("network");
        return new Lan(active,scope(properties),properties.getInterfaceName(),capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI));
    }
    LocalDiscovery(Context context,Lan selected){app=context.getApplicationContext();this.selected=selected;}
    private boolean stillSelected(){try{return selected.same(current(app));}catch(Exception unavailable){return false;}}
    private boolean legacyNsdSingleLan(){
        for(Network n:cm.getAllNetworks()){
            NetworkCapabilities c=cm.getNetworkCapabilities(n);
            if(!n.equals(network)&&c!=null&&(eligible(c)||c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)))return false;
        }
        return true;
    }
    private boolean nsdAllowed(){return Build.VERSION.SDK_INT>=33||legacyNsdSingleLan();}
    @Override public void start(Listener l) {
        listener=l;
        try {
            cm=app.getSystemService(ConnectivityManager.class);
            if(!stillSelected())throw new IOException("network");
            network=selected.network;scope=selected.scope;
            deadline=SystemClock.elapsedRealtime()+DURATION_MS;main.postDelayed(expiry,DURATION_MS);
            nsd=app.getSystemService(NsdManager.class);
            if(selected.wifi)try{
                WifiManager wifi=(WifiManager)app.getSystemService(Context.WIFI_SERVICE);
                multicast=wifi.createMulticastLock("moli-manual-discovery");multicast.setReferenceCounted(false);multicast.acquire();
            }catch(Exception unavailable){warning();}
            callback=new ConnectivityManager.NetworkCallback() {
                @Override public void onLost(Network n) { if(n.equals(network)) main.post(() -> finish(End.NETWORK_CHANGED)); }
                @Override public void onAvailable(Network n) { if(!n.equals(network)) main.post(() -> finish(End.NETWORK_CHANGED)); }
                @Override public void onCapabilitiesChanged(Network n,NetworkCapabilities c) {
                    if(n.equals(network) && !eligible(c)) main.post(() -> finish(End.NETWORK_CHANGED));
                }
                @Override public void onLinkPropertiesChanged(Network n,LinkProperties properties) {
                    if(!n.equals(network)) return;
                    try { NetworkEvidence.Scope s=scope(properties); if(s.own!=scope.own || s.prefix!=scope.prefix || !selected.iface.equals(properties.getInterfaceName())) main.post(() -> finish(End.NETWORK_CHANGED)); }
                    catch(Exception e) { main.post(() -> finish(End.NETWORK_CHANGED)); }
                }
            };
            cm.registerDefaultNetworkCallback(callback); registered=true;
            if(nsdAllowed()){
                discover("_ipp._tcp.");discover("_http._tcp.");discover("_googlecast._tcp.");
            }else warning(); // Legacy NSD cannot select a network; never mix simultaneous LANs.
            announcements.execute(() -> ssdp(new InetSocketAddress("239.255.255.250",1900)));
            List<String> hosts=scope.probeHosts();if(scope.probesLimited())warning();
            for(String host:hosts)for(int port:new int[]{80,443,631})io.execute(() -> probe(host,port));
        } catch(Exception failure) { finish(End.UNAVAILABLE); }
    }
    static NetworkEvidence.Scope scope(LinkProperties properties) throws IOException {
        if(properties!=null) for(LinkAddress address:properties.getLinkAddresses()) {
            if(address.getAddress() instanceof Inet4Address && NetworkEvidence.privateIp(NetworkEvidence.ipv4(address.getAddress().getHostAddress())))
                return new NetworkEvidence.Scope(address.getAddress().getHostAddress(),address.getPrefixLength());
        }
        throw new IOException("LAN IPv4 unavailable");
    }
    private boolean active() { return !closed.get() && SystemClock.elapsedRealtime()<deadline; }
    private void emit(NetworkEvidence.Entry e) {
        if(e==null||!active()||!scope.contains(e.ip))return;
        main.post(()->{if(active()&&listener!=null)listener.found(e);});
    }
    private void warning() { main.post(()->{if(active()&&listener!=null)listener.warning();}); }
    private void discover(String type) {
        NsdManager.DiscoveryListener discovery=new NsdManager.DiscoveryListener() {
            public void onDiscoveryStarted(String t) {}
            public void onDiscoveryStopped(String t) {}
            public void onStartDiscoveryFailed(String t,int error) { main.post(() -> { if(active()) warning(); }); }
            public void onStopDiscoveryFailed(String t,int error) {}
            public void onServiceLost(NsdServiceInfo info) {}
            public void onServiceFound(NsdServiceInfo info) { main.post(() -> {
                if(!active() || seenServices.size()>=128 || !(type.equals(info.getServiceType()) || type.equals(info.getServiceType()+"."))) return;
                if(!nsdAllowed()){warning();return;}
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
                if(active() && nsdAllowed() && address instanceof Inet4Address && info.getPort()>0 && info.getPort()<=65535) {
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
            if(!track(socket))return;network.bindSocket(socket);socket.bind(new InetSocketAddress(NetworkEvidence.address(scope.own),0)); socket.setSoTimeout(350);
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
                NetworkEvidence.Entry fact=NetworkEvidence.ssdp(source,raw);
                if(fact==null)continue;emit(fact);
                if(fact.path.toLowerCase(Locale.ROOT).endsWith(".xml")&&seenDescriptions.size()<32&&seenDescriptions.add(fact.location))io.execute(()->describe(fact));
            }
        } catch(Exception failure) { warning(); }
    }
    private void describe(NetworkEvidence.Entry fact) {
        if(!active())return;
        Socket socket=new Socket();
        try(socket){
            if(!track(socket))return;network.bindSocket(socket);socket.bind(new InetSocketAddress(NetworkEvidence.address(scope.own),0));socket.setSoTimeout(1000);
            if(!active())return;socket.connect(new InetSocketAddress(fact.ip,fact.port),1000);
            String xml=DiscoveryHttp.description(socket,fact.ip,fact.port,fact.path);
            emit(DiscoveryXml.describe(fact,xml));
        }catch(Exception unavailable){/* Announcement stays visible; no private diagnostic. */}
        finally{sockets.remove(socket);}
    }

    private void probe(String host,int port) {
        if(!active()) return;
        Socket socket=new Socket();
        try(socket) {
            if(!track(socket))return;network.bindSocket(socket);socket.bind(new InetSocketAddress(NetworkEvidence.address(scope.own),0));
            if(!active()) return;
            socket.connect(new InetSocketAddress(host,port),200);
            emit(new NetworkEvidence.Entry(host,port,"",NetworkEvidence.Kind.TCP,NetworkEvidence.Proof.OPEN_PORT,"",""));
        } catch(Exception unavailable) { /* A silent host is not proof that it is absent. */ }
        finally{sockets.remove(socket);}
    }
    private boolean track(Closeable socket)throws IOException {
        synchronized(sockets){if(!active()){socket.close();return false;}sockets.add(socket);return true;}
    }
    private void finish(End reason) {
        if(!closed.compareAndSet(false,true)) return;
        main.removeCallbacks(expiry);
        synchronized(sockets){for(Closeable socket:sockets)try{socket.close();}catch(IOException ignored){}sockets.clear();}
        io.shutdownNow();announcements.shutdownNow();
        if(registered) try { cm.unregisterNetworkCallback(callback); } catch(Exception ignored) {}
        for(NsdManager.DiscoveryListener l:nsdListeners) try { nsd.stopServiceDiscovery(l); } catch(Exception ignored) {}
        if(Build.VERSION.SDK_INT>=34 && resolving && resolver!=null) try { nsd.stopServiceResolution(resolver); } catch(Exception ignored) {}
        nsdListeners.clear(); resolveQueue.clear();
        if(multicast!=null && multicast.isHeld()) multicast.release();
        Listener ended=listener;listener=null;if(ended!=null)ended.finished(reason);
    }
    @Override public void close() { finish(End.CANCELLED); }
}
