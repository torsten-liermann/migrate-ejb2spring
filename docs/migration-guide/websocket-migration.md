# Jakarta WebSocket zu Spring WebSocket – Migrationshinweise

Das Rezept `MarkWebSocketForMigration` markiert Klassen mit `@ServerEndpoint` oder `@ClientEndpoint` durch `@NeedsReview`. Die folgenden Optionen dienen als Orientierung für die Migration.

## Optionen im Projektkontext

| Option | Beschreibung | Hinweis |
|---|---|---|
| A | `@ServerEndpoint` beibehalten und im Spring‑Boot‑Runtime betreiben | Separater WebSocket‑Container, keine Spring‑Injection ohne zusätzliche Konfiguration |
| B | Migration auf Spring‑WebSocket‑Handler | Umstellung auf Spring‑API erforderlich |

## Option A: `@ServerEndpoint` beibehalten

Beispiel: Jakarta WebSocket
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

Beispiel: Spring Boot mit `@ServerEndpoint`
```java
import jakarta.websocket.server.ServerEndpoint;

@ServerEndpoint("/tracking/{channel}")
public class TrackingEndpoint {
}
```

Wird `ServerEndpointExporter` verwendet, erstellt der WebSocket‑Container die Endpoint‑Instanzen. Spring‑Injection steht in diesem Modell nur über einen Configurator zur Verfügung und muss projektspezifisch geprüft werden.

Beispiel: `ServerEndpointExporter`
```java
@Configuration
public class WebSocketConfig {

    @Bean
    public ServerEndpointExporter serverEndpointExporter() {
        return new ServerEndpointExporter();
    }
}
```

Bei WAR‑Deployments in externe Container können Konflikte mit der container‑eigenen Endpoint‑Registrierung entstehen. Ob diese Option im Zielbetrieb zulässig ist, ist projektspezifisch zu entscheiden.

## Option B: Spring‑WebSocket‑Handler

Beispiel: Spring‑Handler
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

Beispiel: Registrierung
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

Spring WebSocket nutzt eine Handler‑API statt Annotationen wie `@OnMessage`. Pfadparameter müssen aus der URI extrahiert werden.

## Begriffliche Zuordnung (Orientierung)

| Jakarta WebSocket | Spring WebSocket |
|---|---|
| `@ServerEndpoint` | `WebSocketHandler` + Konfiguration |
| `@OnOpen` | `afterConnectionEstablished()` |
| `@OnMessage` | `handleTextMessage()` / `handleBinaryMessage()` |
| `@OnClose` | `afterConnectionClosed()` |
| `@OnError` | `handleTransportError()` |
| `Session` | `WebSocketSession` |

## Quellen

- https://jakarta.ee/specifications/websocket/
- https://docs.spring.io/spring-framework/reference/web/websocket.html
