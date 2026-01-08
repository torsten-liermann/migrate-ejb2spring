# JMS-Migration – Strategie und Konfiguration

Die Migration von JMS-basierten EJB-Anwendungen zu Spring Boot erfordert sowohl Code-Transformationen als auch Infrastruktur-Konfiguration. Diese Dokumentation beschreibt die konkrete Strategie.

## Strategie: Spring JMS mit Artemis

Die empfohlene Migrationsstrategie verwendet **Spring JMS** mit **Apache Artemis** als Messaging-Provider:

| Aspekt | Jakarta EE | Spring Boot |
|--------|-----------|-------------|
| API | `@MessageDriven` | `@JmsListener` |
| Provider | Application Server (WildFly/WebLogic) | Apache Artemis |
| Konfiguration | Activation Properties | application.properties |
| Connection Factory | JNDI-Lookup | Auto-Configuration |

Diese Strategie wurde gewählt, weil:
- Spring Boot bietet native Artemis-Integration via `spring-boot-starter-artemis`
- Artemis ist kompatibel mit bestehenden JMS-1.1- und JMS-2.0-Anwendungen
- Die Migration erfordert minimale Code-Änderungen

## Abhängigkeiten

Die automatische Migration fügt `spring-boot-starter-artemis` hinzu, wenn JMS-Nutzung erkannt wird:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-artemis</artifactId>
</dependency>
```

## Code-Transformation

### Message-Driven Beans

```java
// VORHER: EJB
@MessageDriven(activationConfig = {
    @ActivationConfigProperty(propertyName = "destinationType",
                              propertyValue = "javax.jms.Queue"),
    @ActivationConfigProperty(propertyName = "destinationLookup",
                              propertyValue = "jms/OrderQueue")
})
public class OrderListener implements MessageListener {
    @Override
    public void onMessage(Message message) { ... }
}

// NACHHER: Spring Boot
@Component
public class OrderListener {
    @JmsListener(destination = "OrderQueue")
    public void onMessage(OrderCommand command) { ... }
}
```

### ConnectionFactory

Die ConnectionFactory wird durch Spring Boot Auto-Configuration bereitgestellt:

```properties
# application.properties
spring.artemis.mode=native
spring.artemis.broker-url=tcp://localhost:61616
spring.artemis.user=admin
spring.artemis.password=admin
```

Explizite JNDI-Lookups für ConnectionFactory entfallen.

### Queue-/Topic-Definitionen

JNDI-basierte Destination-Lookups werden zu Bean-Definitionen:

```java
// VORHER: JNDI-Lookup
@Resource(lookup = "jms/CargoHandledQueue")
private Queue cargoQueue;

// NACHHER: Spring Configuration
@Configuration
public class JmsConfiguration {
    @Bean
    public Queue cargoHandledQueue() {
        return new ActiveMQQueue("CargoHandledQueue");
    }
}
```

Die Queue-Bean-Klasse hängt vom Provider ab:
- Artemis: `org.apache.activemq.artemis.jms.client.ActiveMQQueue`
- ActiveMQ Classic: `org.apache.activemq.command.ActiveMQQueue`

## Konfiguration

### Basis-Konfiguration

```properties
# application.properties

# Artemis-Verbindung
spring.artemis.mode=native
spring.artemis.broker-url=tcp://localhost:61616
spring.artemis.user=${JMS_USER:admin}
spring.artemis.password=${JMS_PASSWORD:admin}

# Listener-Container
spring.jms.listener.auto-startup=true
spring.jms.listener.acknowledge-mode=auto
spring.jms.listener.concurrency=5
spring.jms.listener.max-concurrency=10
```

### Transaktionen

EJB-MDBs nutzen Container-Managed Transactions. In Spring wird dies durch `@Transactional` abgebildet:

```java
@JmsListener(destination = "OrderQueue")
@Transactional
public void processOrder(OrderCommand command) {
    // Fehler führen zu Rollback und Redelivery
}
```

### Error-Handling und Redelivery

Redelivery-Verhalten muss explizit konfiguriert werden. Die Standardwerte unterscheiden sich vom Application-Server:

```properties
# Artemis-spezifische Redelivery-Einstellungen
# (erfordern Broker-Konfiguration, nicht application.properties)
```

Für Dead Letter Queue und Redelivery-Backoff ist Broker-Konfiguration erforderlich.

## Migrierte Rezepte

| Rezept | Funktion |
|--------|----------|
| `MigrateMessageDrivenToJmsListener` | `@MessageDriven` → `@Component` + `@JmsListener` |
| `MigrateJmsConnectionFactory` | Entfernt JNDI-basierte ConnectionFactory |
| `MigrateJmsDestination` | Generiert JmsConfiguration für Queue-Beans |
| `AddEnableJmsAndScheduling` | Fügt `@EnableJms` zur Application-Klasse hinzu |
| `MarkJmsContextForReview` | Markiert JMSContext-Nutzung für manuelle Prüfung |

## Manuelle Nacharbeiten

Nach der automatischen Migration sind folgende Schritte erforderlich:

1. **Artemis-Konfiguration prüfen**: Broker-URL und Credentials in application.properties setzen
2. **Queue-Namen anpassen**: Die generierten Queue-Bean-Namen gegen die tatsächliche Broker-Konfiguration prüfen
3. **Redelivery-Strategie**: Broker-seitige Konfiguration für Dead Letter Queues und Backoff
4. **Message Converter**: Falls nötig, JMS-MessageConverter für komplexe Objekt-Serialisierung konfigurieren

## Quellen

- https://docs.spring.io/spring-framework/reference/integration/jms.html
- https://docs.spring.io/spring-boot/reference/messaging/jms.html
- https://activemq.apache.org/components/artemis/documentation/
