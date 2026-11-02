package com.doumiot.sshconsole.update;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class UpdateHttpsProxyTest {
    @TempDir Path dir;
    @Test void connectTunnelPreservesTlsVerificationAndDoesNotRetryCertificateFailures()throws Exception {
        Path store=dir.resolve("fixture.p12");char[] password="fixture-password".toCharArray();
        Process keytool=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","keytool").toString(),
                "-genkeypair","-alias","fixture","-keyalg","RSA","-keysize","2048","-storetype","PKCS12",
                "-keystore",store.toString(),"-storepass",new String(password),"-dname","CN=localhost","-ext","SAN=dns:localhost","-validity","2")
                .redirectErrorStream(true).redirectOutput(dir.resolve("keytool.log").toFile()).start();
        assertTrue(keytool.waitFor(30,TimeUnit.SECONDS));assertEquals(0,keytool.exitValue());
        KeyStore keys=KeyStore.getInstance("PKCS12");try(InputStream in=Files.newInputStream(store)){keys.load(in,password);}
        KeyManagerFactory km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(keys,password);
        TrustManagerFactory tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(keys);
        SSLContext tls=SSLContext.getInstance("TLS");tls.init(km.getKeyManagers(),tm.getTrustManagers(),null);
        HttpsServer server=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setHttpsConfigurator(new HttpsConfigurator(tls));
        server.createContext("/asset",x->{assertNull(x.getRequestHeaders().getFirst("Authorization"));byte[] b={7,8,9};x.sendResponseHeaders(200,b.length);x.getResponseBody().write(b);x.close();});
        server.start();SSLSocketFactory original=HttpsURLConnection.getDefaultSSLSocketFactory();
        try(Tunnel proxy=new Tunnel(server.getAddress().getPort())) {
            String address="https://localhost:"+server.getAddress().getPort()+"/asset";
            Path failed=dir.resolve("untrusted");
            assertThrows(SSLHandshakeException.class,()->UpdateDownload.download(address,proxy.address(),failed,10,n->{},1000,0));
            assertEquals(1,proxy.requests.get());assertFalse(Files.exists(failed));
            HttpsURLConnection.setDefaultSSLSocketFactory(tls.getSocketFactory());
            Path target=dir.resolve("trusted");UpdateDownload.download(address,proxy.address(),target,10,n->{});
            assertEquals(2,proxy.requests.get());assertArrayEquals(new byte[]{7,8,9},Files.readAllBytes(target));
        }finally{HttpsURLConnection.setDefaultSSLSocketFactory(original);server.stop(0);}
    }
    private static final class Tunnel implements AutoCloseable {
        final ServerSocket listener;final ExecutorService workers=Executors.newCachedThreadPool();final AtomicInteger requests=new AtomicInteger();
        volatile boolean closed;
        Tunnel(int targetPort)throws IOException {
            listener=new ServerSocket(0,10,InetAddress.getByName("127.0.0.1"));
            workers.submit(()->{while(!closed)try{Socket client=listener.accept();workers.submit(()->forward(client,targetPort));}catch(IOException ignored){if(!closed)throw new UncheckedIOException(ignored);}});
        }
        String address(){return "http://127.0.0.1:"+listener.getLocalPort();}
        void forward(Socket client,int port) {
            try(Socket incoming=client;Socket outgoing=new Socket("127.0.0.1",port)) {
                incoming.setSoTimeout(5000);outgoing.setSoTimeout(5000);
                BufferedReader reader=new BufferedReader(new InputStreamReader(incoming.getInputStream(),StandardCharsets.US_ASCII));
                String first=reader.readLine();if(!("CONNECT localhost:"+port+" HTTP/1.1").equals(first))throw new IOException("Unexpected CONNECT request");
                for(String line;(line=reader.readLine())!=null&&!line.isEmpty();){}
                requests.incrementAndGet();incoming.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));incoming.getOutputStream().flush();
                Future<?> upload=workers.submit(()->pump(incoming,outgoing));pump(outgoing,incoming);upload.get(5,TimeUnit.SECONDS);
            }catch(Exception ignored){ /* Closing either end terminates a failed TLS handshake. */ }
        }
        static void pump(Socket from,Socket to){try{from.getInputStream().transferTo(to.getOutputStream());}catch(IOException ignored){}finally{try{to.shutdownOutput();}catch(IOException ignored){}}}
        public void close()throws Exception{closed=true;listener.close();workers.shutdownNow();assertTrue(workers.awaitTermination(10,TimeUnit.SECONDS));}
    }
}
