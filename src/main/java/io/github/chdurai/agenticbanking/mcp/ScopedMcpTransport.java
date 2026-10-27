package io.github.chdurai.agenticbanking.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.*;
import io.github.chdurai.agenticbanking.security.CallerContext;
import org.springframework.web.servlet.function.*;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Binds the SSE connection and every JSON-RPC message to the same authenticated principal. */
public final class ScopedMcpTransport implements McpServerTransportProvider, AutoCloseable {
    private record Connection(String tenant, String actor, McpServerSession session) {}
    private final ConcurrentHashMap<String, Connection> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper json;
    private final String messagePath;
    private final RouterFunction<ServerResponse> routes;
    private volatile McpServerSession.Factory factory;
    private volatile boolean closing;

    public ScopedMcpTransport(ObjectMapper json, String ssePath, String messagePath) {
        this.json = json;
        this.messagePath = messagePath;
        this.routes = RouterFunctions.route().GET(ssePath, this::connect).POST(messagePath, this::message).build();
    }

    public RouterFunction<ServerResponse> routes() { return routes; }
    @Override public void setSessionFactory(McpServerSession.Factory factory) { this.factory = factory; }

    private ServerResponse connect(ServerRequest request) {
        if (closing || sessions.size() >= 1000) return ServerResponse.status(503).build();
        if (!CallerContext.isBound()) return ServerResponse.status(401).build();
        var caller = CallerContext.current();
        String id = UUID.randomUUID().toString();
        return ServerResponse.sse(sse -> {
            McpServerTransport transport = new McpServerTransport() {
                @Override public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
                    return Mono.fromRunnable(() -> {
                        try { synchronized (sse) { sse.event("message").data(json.writeValueAsString(message)); } }
                        catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
                    });
                }
                @Override public <T> T unmarshalFrom(Object data, TypeReference<T> type) { return json.convertValue(data, type); }
                @Override public Mono<Void> closeGracefully() { return Mono.fromRunnable(this::close); }
                @Override public void close() { sessions.remove(id); sse.complete(); }
            };
            var session = factory.create(transport);
            sessions.put(id, new Connection(caller.tenantId(), caller.actor(), session));
            sse.onComplete(() -> sessions.remove(id));
            sse.onError(error -> {
                var removed = sessions.remove(id);
                if (removed != null) removed.session().close();
            });
            try { sse.event("endpoint").data(messagePath + "?sessionId=" + id); }
            catch (java.io.IOException e) {
                session.close();
                throw new java.io.UncheckedIOException(e);
            }
        }, Duration.ZERO);
    }

    private ServerResponse message(ServerRequest request) throws java.io.IOException, jakarta.servlet.ServletException {
        String id = request.param("sessionId").orElse("");
        var connection = sessions.get(id);
        if (connection == null) return ServerResponse.status(404).build();
        var caller = CallerContext.current();
        if (!CallerContext.isBound() || !connection.tenant().equals(caller.tenantId())
                || !connection.actor().equals(caller.actor())) {
            return ServerResponse.status(403).build();
        }
        McpSchema.JSONRPCMessage message;
        try { message = McpSchema.deserializeJsonRpcMessage(json, request.body(String.class)); }
        catch (IllegalArgumentException | com.fasterxml.jackson.core.JsonProcessingException e) {
            return ServerResponse.badRequest().build();
        }
        connection.session().handle(message).contextCapture().block(Duration.ofSeconds(30));
        return ServerResponse.ok().build();
    }

    @Override public Mono<Void> notifyClients(String method, Object params) {
        return reactor.core.publisher.Flux.fromIterable(sessions.values())
                .flatMap(c -> c.session().sendNotification(method, params)).then();
    }

    @Override public Mono<Void> closeGracefully() {
        closing = true;
        return reactor.core.publisher.Flux.fromIterable(sessions.values())
                .flatMap(c -> c.session().closeGracefully()).then();
    }

    @Override public void close() {
        closing = true;
        for (var connection : java.util.List.copyOf(sessions.values())) connection.session().close();
        sessions.clear();
    }
}
