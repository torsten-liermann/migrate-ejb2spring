# Stateful Session Bean Migration – Entwurf

## Status

Entscheidung getroffen: No-Op-Marker als Default, Session-Scope als Opt-in.

## Referenz

Die konzeptionelle Dokumentation befindet sich im Hauptkapitel:

**[docs/chapters/_02-session-beans.adoc](chapters/_02-session-beans.adoc)** – Abschnitt „@Stateful -> Explizite Zustands-Architektur" (ab Zeile 253)

## Ausgangslage

Stateful Session Beans halten Konversationszustand. Der Zustand ist nicht an HTTP gebunden, sondern an den Client und die EJB-Konversation. Die Zielabbildung haengt am Client-Typ und der beabsichtigten Lebensdauer des Zustands, nicht allein am Annotationstyp.

Eine pauschale Abbildung auf HTTP-Session-Scope ist nur in Web-Kontexten zulaessig.

## Transaktionen und Ressourcenbindung

Stateful Beans werden typischerweise methodenweise transaktional ausgefuehrt. Transaktionskontexte sind an Aufrufe gebunden und koennen nicht ueber HTTP-Requests hinweg erwartet werden. Ressourcenbindungen wie offene JDBC-Connections oder nicht serialisierbare EntityManager-Instanzen sind in externen Session-Stores nicht haltbar.

## Recipe-Strategie

### Grundsatz

Minimierung von `@NeedsReview`. Jedes Review erfordert manuellen Aufwand und widerspricht dem Ziel der automatischen Migration. Stattdessen: No-Op-Marker zur Sichtbarkeit, Transformation nur per Opt-in.

### Standardverhalten (Default)

| EJB Annotation     | Transformation                       | @NeedsReview |
|--------------------|--------------------------------------|--------------|
| `@Stateful`        | `@EjbStateful` (No-Op-Marker)        | Nein         |
| `@Remove`          | `@EjbRemove` (No-Op-Marker)          | Nein         |
| `@PostActivate`    | `@EjbPostActivate` (No-Op-Marker)    | Nein         |
| `@PrePassivate`    | `@EjbPrePassivate` (No-Op-Marker)    | Nein         |
| `@StatefulTimeout` | `@EjbStatefulTimeout` (No-Op-Marker) | Nein         |

**Beispiel (Default):**
```java
// Vorher
@Stateful
public class CounterBean { ... }

// Nachher
@EjbStateful
public class CounterBean { ... }
```

### Opt-in: Session-Scope-Transformation

Aktivierung ueber Projektkonfiguration: `stateful.strategy = session`

| EJB Annotation     | Transformation                                          | @NeedsReview |
|--------------------|---------------------------------------------------------|--------------|
| `@Stateful`        | `@Component` + `@Scope(session, proxyMode=TARGET_CLASS)`| Nein         |
| `@Remove`          | `@PreDestroy`                                           | Nein         |
| `@PostActivate`    | Entfernt                                                | Nein         |
| `@PrePassivate`    | Entfernt                                                | Nein         |
| `@StatefulTimeout` | Entfernt (Session-Timeout via application.properties)   | Nein         |

**Beispiel (Opt-in session):**
```java
// Vorher
@Stateful
public class CounterBean {
    @Remove
    public void close() { }
}

// Nachher (stateful.strategy = session)
@Component
@Scope(value = WebApplicationContext.SCOPE_SESSION, proxyMode = ScopedProxyMode.TARGET_CLASS)
public class CounterBean {
    @PreDestroy
    public void close() { }
}
```

## Migrationsansaetze (Hintergrund)

### 1. Session Scoped Beans
- `@Component` + `@Scope("session")`
- Nur fuer HTTP-basierte Clients
- Skalierung erfordert Session-Store oder Session-Affinity

### 2. Spring Session + Redis
- State in Redis statt JVM-Heap
- Serialisierbarkeit erforderlich

### 3. Database-Backed State
- State explizit in Datenbank persistiert
- Fuer komplexe Business-Prozesse

## Recipe-Struktur

- **Separates Recipe:** `MigrateStatefulToMarker` (Default, immer aktiv)
- **Separates Recipe:** `MigrateStatefulToSessionScope` (Opt-in)
- **Nicht im Haupt-Recipe:** Aktivierung nur bei expliziter Konfiguration

## Naechste Schritte

- [x] Entscheidung: No-Op-Marker als Default, Session-Scope als Opt-in
- [x] No-Op-Marker existieren bereits: `@EjbStateful`, `@EjbRemove`, `@EjbPostActivate`, `@EjbPrePassivate`, `@EjbStatefulTimeout` in `com.github.migration.annotations`
- [x] `MapEjbAnnotationsToMarkers` mappt alle Stateful-Annotationen
- [ ] Recipe `MigrateStatefulToSessionScope` implementieren (Opt-in)
- [ ] Tests fuer Session-Scope-Migration schreiben
- [ ] Dokumentation aktualisieren

---
Erstellt: 2026-01-19
