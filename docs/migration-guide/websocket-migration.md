# Jakarta WebSocket to Spring WebSocket migration notes
The `MarkWebSocketForMigration` recipe marks classes with `@ServerEndpoint` or `@ClientEndpoint` with `@NeedsReview`. The following options serve as a guide for migration.
## Options in project context
| option | Description | Note |
|---|---|---|
| A | Keep `@ServerEndpoint` and run it in the Spring Boot runtime | Separate WebSocket container, no Spring injection without additional configuration |
| B | Migrating to Spring WebSocket Handler | Switching to Spring API required |
## Option A: Keep `@ServerEndpoint`
Example: Jakarta WebSocket
```java
import jakarta.websocket.*;
import jakarta.websocket.server.ServerEndpoint;

@ServerEndpoint("/tracking/{channel}")
public class TrackingEndpoint {

    @OnOpen
    public void onOpen(Session session) {
    }

    @OnMessage
    public void onMessage(String message, Session session) {
        session.getBasicRemote().sendText("Ack: " + message);
    }
}
```

Example: Spring Boot with `@ServerEndpoint`
```java
import jakarta.websocket.server.ServerEndpoint;

@ServerEndpoint("/tracking/{channel}")
public class TrackingEndpoint {
}
```

When `ServerEndpointExporter` is used, the WebSocket container creates the endpoint instances. In this model, Spring Injection is only available via a configurator and must be checked on a project-specific basis.
Example: `ServerEndpointExporter`
```java
@Configuration
public class WebSocketConfig {

    @Bean
    public ServerEndpointExporter serverEndpointExporter() {
        return new ServerEndpointExporter();
    }
}
```

WAR deployments into external containers can cause conflicts with the container's own endpoint registry. Whether this option is permitted in the target operation must be decided on a project-specific basis.
## Option B: Spring WebSocket handler
Example: Spring handler
```java
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class TrackingHandler extends TextWebSocketHandler {

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        session.sendMessage(new TextMessage("Ack: " + message.getPayload()));
    }
}
```

Example: Registration
```java
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final TrackingHandler trackingHandler;

    public WebSocketConfig(TrackingHandler trackingHandler) {
        this.trackingHandler = trackingHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(trackingHandler, "/tracking/*");
    }
}
```

Spring WebSocket uses a handler API instead of annotations like @OnMessage. Path parameters must be extracted from the URI.
## Conceptual assignment (orientation)
| Jakarta WebSocket | Spring WebSocket |
|---|---|
| `@ServerEndpoint` | `WebSocketHandler` + configuration |
| `@OnOpen` | `afterConnectionEstablished()` |
| `@OnMessage` | `handleTextMessage()` / `handleBinaryMessage()` |
| `@OnClose` | `afterConnectionClosed()` |
| `@OnError` | `handleTransportError()` |
| `Session` | `WebSocketSession` |
## Sources
- https://jakarta.ee/specifications/websocket/
- https://docs.spring.io/spring-framework/reference/web/websocket.html
