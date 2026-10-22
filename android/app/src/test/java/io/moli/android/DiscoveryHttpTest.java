package io.moli.android;

import org.junit.Test;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** A loopback fixture verifies actual HTTP bytes, framing, bounds and no redirect following. */
public final class DiscoveryHttpTest {
    private String fetch(String response) throws Exception {
        try(ServerSocket server=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {
            ExecutorService io=Executors.newSingleThreadExecutor();
            Future<String> request=io.submit(() -> {
                try(Socket s=server.accept()) {
                    s.setSoTimeout(2000); ByteArrayOutputStream b=new ByteArrayOutputStream(); int c;
                    while((c=s.getInputStream().read())!=-1) { b.write(c); if(b.toString(StandardCharsets.US_ASCII.name()).endsWith("\r\n\r\n")) break; if(b.size()>2048) throw new IOException(); }
                    s.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII)); s.getOutputStream().flush(); return b.toString(StandardCharsets.US_ASCII.name());
                }
            });
            try(Socket socket=new Socket(InetAddress.getLoopbackAddress(),server.getLocalPort())) {
                socket.setSoTimeout(2000);
                try { return DiscoveryHttp.description(socket,"192.168.1.20",8080,"/root.xml"); }
                finally {
                    String wire=request.get(3,TimeUnit.SECONDS);
                    assertTrue(wire.startsWith("GET /root.xml HTTP/1.1\r\nHost: 192.168.1.20:8080\r\n"));
                    assertFalse(wire.contains("Authorization")); assertFalse(wire.contains("POST"));
                }
            } finally { io.shutdownNow(); }
        }
    }
    @Test public void readsFixedChunkedAndCloseDelimitedXmlWithOnlyOneGet() throws Exception {
        assertEquals("<root/>",fetch("HTTP/1.1 200 OK\r\nContent-Length: 7\r\n\r\n<root/>"));
        assertEquals("<root/>",fetch("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\n<ro\r\n4\r\not/>\r\n0\r\n\r\n"));
        assertEquals("<root/>",fetch("HTTP/1.0 200 OK\r\n\r\n<root/>"));
    }
    @Test public void rejectsRedirectsHugeAndAmbiguousResponses() throws Exception {
        for(String response:new String[]{
            "HTTP/1.1 302 Found\r\nLocation: http://example.org/\r\nContent-Length: 0\r\n\r\n",
            "HTTP/1.1 200 OK\r\nContent-Length: 99999\r\n\r\n",
            "HTTP/1.1 200 OK\r\nContent-Length: 7\r\nContent-Length: 7\r\n\r\n<root/>",
            "HTTP/1.1 200 OK\r\nContent-Length: 7\r\nTransfer-Encoding: chunked\r\n\r\n<root/>",
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n10000\r\n",
            "HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\n\r\n"}) {
            try { fetch(response); fail("Unsafe response accepted"); } catch(IOException expected) {}
        }
    }
}
