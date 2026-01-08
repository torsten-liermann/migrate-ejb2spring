# CDI‑Events‑Analyse – Cargo Tracker

## Zusammenfassung

In Cargo Tracker wird ein CDI‑Event‑Pattern verwendet. Es handelt sich um ein qualifiziertes Event für Cargo‑Inspektionen, das von einem Service ausgelöst und von einem WebSocket‑Listener konsumiert wird.

## Fundstellen (Analysestand)

### CDI‑Qualifier‑Definition

Datei: `src/main/java/org/eclipse/cargotracker/infrastructure/events/cdi/CargoInspected.java`

```java
@Qualifier
@Retention(RUNTIME)
@Target({FIELD, PARAMETER})
public @interface CargoInspected {}
```

### Event Publisher

Datei: `src/main/java/org/eclipse/cargotracker/application/internal/DefaultCargoInspectionService.java`

```java
@Inject
@CargoInspected
private Event<Cargo> cargoInspected;

cargoInspected.fire(cargo);
```

### Event Observer

Datei: `src/main/java/org/eclipse/cargotracker/interfaces/booking/socket/RealtimeCargoTrackingService.java`

```java
public void onCargoInspected(@Observes @CargoInspected Cargo cargo) {
    for (Session session : sessions) {
        session.getBasicRemote().sendText(jsonValue);
    }
}
```

### Test‑Stub

Datei: `src/test/java/org/eclipse/cargotracker/interfaces/booking/socket/CargoInspectedStub.java`

```java
@Inject
@CargoInspected
Event<Cargo> cargoEvent;

@Timeout
public void raiseEvent(Timer timer) {
    cargoEvent.fire(new Cargo(...));
}
```

## Transformation (Orientierung)

| CDI | Spring |
|---|---|
| `@Inject Event<Cargo>` | `ApplicationEventPublisher` |
| `event.fire(cargo)` | `publisher.publishEvent(cargo)` |
| `@Observes` auf Parameter | `@EventListener` auf Methode |
| CDI‑Qualifier | `@NeedsReview` (kein direktes Äquivalent) |

## Auswirkungen für die Migration

Die CDI‑Qualifier‑Semantik wird durch `@NeedsReview` markiert, damit die Zuordnung fachlich geprüft werden kann.
