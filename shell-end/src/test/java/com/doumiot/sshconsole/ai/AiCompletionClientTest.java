package com.doumiot.sshconsole.ai;

import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class AiCompletionClientTest {
    HttpServer server;AiCompletionClient client=new AiCompletionClient();AiProviderService.Credentials provider=new AiProviderService.Credentials();
    @BeforeEach void setup() throws Exception {server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.start();provider.endpoint="http://127.0.0.1:"+server.getAddress().getPort()+"/v1/chat/completions";provider.model="test-model";provider.apiKey="test-provider-key";}
    @AfterEach void close(){server.stop(0);}
    @Test void sendsCompatibleMessagesAndParsesToolCalls() {
        AtomicReference<JsonNode> received=new AtomicReference<>();AtomicReference<String> auth=new AtomicReference<>();
        server.createContext("/v1/chat/completions",exchange->{received.set(AiJson.JSON.readTree(exchange.getRequestBody()));auth.set(exchange.getRequestHeaders().getFirst("Authorization"));byte[] body="{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"t1\",\"type\":\"function\",\"function\":{\"name\":\"data_tables\",\"arguments\":\"{}\"}}]}}]}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});
        JsonNode reply=client.complete(provider,Collections.singletonList(AiJson.object("role","user","content","test")),AiJson.JSON.createArrayNode().add(AiJson.object("type","function")));
        assertEquals("test-provider-key",auth.get().substring(7));assertEquals("test-model",received.get().path("model").asText());assertFalse(received.get().path("stream").asBoolean());assertEquals("t1",reply.path("tool_calls").path(0).path("id").asText());
    }
    @Test void redirectsAreNeverFollowed() {
        AtomicInteger redirected=new AtomicInteger();server.createContext("/v1/chat/completions",exchange->{exchange.getResponseHeaders().set("Location","/redirected");exchange.sendResponseHeaders(302,-1);exchange.close();});
        server.createContext("/redirected",exchange->{redirected.incrementAndGet();exchange.sendResponseHeaders(200,-1);exchange.close();});
        assertThrows(IllegalArgumentException.class,()->client.complete(provider,Collections.emptyList(),null));assertEquals(0,redirected.get());
    }
    @Test void upstreamErrorsDoNotExposeResponseSecrets() {
        server.createContext("/v1/chat/completions",exchange->{byte[] body="sensitive upstream test-provider-key".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(401,body.length);exchange.getResponseBody().write(body);exchange.close();});
        Exception e=assertThrows(IllegalArgumentException.class,()->client.complete(provider,Collections.emptyList(),null));assertTrue(e.getMessage().contains("401"));assertFalse(e.getMessage().contains("test-provider-key"));assertFalse(e.getMessage().contains("sensitive"));
    }
    @Test void invalidProviderAddressesAreRejectedAndFullEndpointIsSupported() {
        for(String url:new String[]{"file:///tmp/a","https://user:password@example.com/v1","https://example.com/v1?apiKey=secret","https://example.com/v1#key"})assertThrows(IllegalArgumentException.class,()->AiProviderService.normalizeBaseUrl(url));
        assertEquals("https://example.com/v1/chat/completions",AiProviderService.endpoint(AiProviderService.normalizeBaseUrl("https://example.com/v1/")));
        assertEquals("https://example.com/v1/chat/completions",AiProviderService.endpoint("https://example.com/v1/chat/completions"));
    }
}
