# EJB Timer Migration – Entwurf

## Status

Entscheidung getroffen: No-Op-Marker als Default, TaskScheduler/Quartz als Opt-in. Das Projektsteuerungs-YAML kann abweichende Defaults setzen.

## Ausgangslage

EJB-Timer sind containerverwaltete Ressourcen mit Kontextobjekt (`Timer`) und optionaler Persistenz. Spring `@Scheduled` ist methodenbasiert und stellt kein Laufzeit-Handle bereit.

## Semantikabweichungen

| Feature | EJB Timer | Spring @Scheduled |
|---------|-----------|-------------------|
| Methodenparameter | `Timer` erlaubt | Parameterlos erforderlich |
| Persistenz | `persistent=true` (Default) | Nicht persistent |
| Cluster | Container-koordiniert | Jede Instanz fuehrt aus |
| Timer-API | `getInfo()`, `cancel()`, etc. | Nicht verfuegbar |

## Recipe-Strategie

### Grundsatz

Minimierung von `@NeedsReview`. Automatische Migration wo moeglich, No-Op-Marker wo nicht, Transformation nur per Opt-in.

### Projektsteuerung (project YAML)

Die Migrationsstrategie wird über die Projektkonfiguration gesteuert. Beispielhafte Schluessel:

```yaml
migration:
  timer:
    strategy: quartz   # scheduled | taskscheduler | quartz
    persistence: required
    cluster: required
```

`strategy` bestimmt den bevorzugten Zielpfad. Ein Projekt kann Quartz als Default setzen, wenn persistente Timer und Cluster-Koordination als Standardanforderung gelten. Die Regeln in der automatischen Migration müssen diese Vorgabe respektieren.

### Auswirkungen der Strategie

- `strategy: scheduled`\
  Nur `@Schedule`-Fälle mit `persistent=false` und ohne Timer-Parameter werden zu `@Scheduled` migriert. Alle anderen Fälle bleiben als No-Op-Marker erhalten.
- `strategy: taskscheduler`\
  `@Schedule`-Fälle mit Timer-Parameter oder Persistenz werden in ein TaskScheduler-Pattern überführt. `@Schedule` ohne Timer-Parameter kann weiterhin zu `@Scheduled` migriert werden.
- `strategy: quartz`\
  `@Schedule`-Fälle mit Persistenz oder Timer-Parameter werden in Quartz-Job/Trigger-Strukturen transformiert. `@Schedule` ohne Persistenz kann optional weiterhin zu `@Scheduled` migriert werden, wenn das Projekt dies zulässt.

### Automatische Migration (immer aktiv)

Migration durch `MigrateScheduleToScheduled` erfolgt **nur wenn ALLE** Bedingungen erfuellt sind:

| Bedingung | Pruefung |
|-----------|----------|
| `persistent = false` | Muss explizit gesetzt sein (EJB-Default ist `true`) |
| Kein Timer-Parameter | Methode darf keinen `Timer`-Parameter haben |
| Keine Non-Literal-Werte | Alle Attribute muessen String-Literale sein |
| Kein `year`-Attribut | `year` darf nicht gesetzt sein (Spring Cron hat kein year) |

```java
// Vorher - erfuellt alle Bedingungen
@Schedule(second = "*/6", minute = "*", hour = "*", persistent = false)
public void simpleTimer() { ... }

// Nachher (automatisch)
@Scheduled(cron = "*/6 * * * * *")
public void simpleTimer() { ... }
```

**Wichtig:** Kein `@NeedsReview` wird generiert. Nicht-migrierbare Faelle werden uebersprungen und von `MapEjbAnnotationsToMarkers` zu `@EjbSchedule` umgewandelt.

### Standardverhalten fuer komplexe Faelle (Default)

| EJB Konstrukt | Transformation | @NeedsReview |
|---------------|----------------|--------------|
| `@Schedule` mit `Timer` Parameter | `@EjbSchedule` (No-Op-Marker) | Nein |
| `@Schedule(persistent=true)` | `@EjbSchedule` (No-Op-Marker) | Nein |
| `@Timeout` | `@EjbTimeout` (No-Op-Marker) | Nein |
| `TimerService` Injection | Unveraendert | Nein |

**Beispiel (Default bei Timer-Parameter):**
```java
// Vorher
@Schedule(second = "*/6", minute = "*", hour = "*", persistent = true)
public void timer(Timer timer) {
    timer.getInfo();
}

// Nachher (Default)
@EjbSchedule(second = "*/6", minute = "*", hour = "*", persistent = true)
public void timer(Timer timer) {
    timer.getInfo();
}
```

### Opt-in: TaskScheduler-Transformation

Aktivierung: `timer.strategy = taskscheduler`

```java
// Nachher (timer.strategy = taskscheduler)
@Service
public class ScheduleExample {

    @Autowired
    private TaskScheduler taskScheduler;

    private ScheduledFuture<?> scheduledTask;
    private final String info = "ScheduleExample";

    @PostConstruct
    public void init() {
        scheduledTask = taskScheduler.scheduleAtFixedRate(
            this::timer, Duration.ofSeconds(6));
    }

    private void timer() {
        // info ueber Member-Variable statt Timer.getInfo()
    }
}
```

### Opt-in: Quartz

Aktivierung: `timer.strategy = quartz`

- Fuegt `spring-boot-starter-quartz` Dependency hinzu
- Generiert `QuartzJobBean` Implementierung
- Fuer persistente Timer mit vollem Funktionsumfang

## EJB-API Dependency

Die No-Op-Marker-Strategie erfordert, dass die EJB-API im migrierten Projekt als Compile-Dependency erhalten bleibt:

```xml
<dependency>
    <groupId>jakarta.ejb</groupId>
    <artifactId>jakarta.ejb-api</artifactId>
    <version>4.0.1</version>
    <scope>compile</scope> <!-- Nicht entfernen! -->
</dependency>
```

**Grund:** Die Marker-Annotationen (`@EjbSchedule`, `@EjbTimeout`) erhalten die Original-Attribute. Methoden mit `Timer`-Parameter behalten diesen. Ohne EJB-API entstehen Compile-Fehler.

**Hinweis:** Die EJB-API ist nur eine API-Jar ohne Laufzeit-Implementierung. Sie fuegt keine EJB-Container-Logik hinzu.

## Abbildung der Timer-Funktionen

| EJB Timer | Quartz | TaskScheduler |
|-----------|--------|---------------|
| `timer.getInfo()` | `context.getJobDataMap()` | Member-Variable |
| `timer.getNextTimeout()` | `context.getNextFireTime()` | Manuell berechnen |
| `timer.cancel()` | `scheduler.deleteJob()` | `future.cancel()` |
| `persistent=true` | Ja (DB) | Nein |

## Entscheidungslogik

| Anforderung | Geeigneter Ansatz |
|-------------|-------------------|
| Einfacher Cron ohne Timer-Nutzung | `@Scheduled` (automatisch) |
| Timer-Parameter im Body | No-Op-Marker oder TaskScheduler (Opt-in) |
| `persistent=true` | No-Op-Marker oder Quartz (Opt-in) |
| Cluster-Koordination | ShedLock + @Scheduled |

## Recipe-Struktur

- **Im Haupt-Recipe:** `MigrateScheduleToScheduled` (einfache Faelle, automatisch)
- **Separates Recipe:** `MigrateTimerToMarker` (komplexe Faelle, Default)
- **Separates Recipe:** `MigrateTimerToTaskScheduler` (Opt-in)
- **Separates Recipe:** `MigrateTimerToQuartz` (Opt-in)

## Naechste Schritte

- [x] Entscheidung: Automatisch wo moeglich, No-Op-Marker sonst, Transformation Opt-in
- [x] `MigrateScheduleToScheduled` angepasst: Nur `persistent=false` explizit migriert
- [x] No-Op-Marker existieren bereits: `@EjbSchedule`, `@EjbTimeout` in `com.github.migration.annotations`
- [x] `MapEjbAnnotationsToMarkers` uebernimmt alle komplexen Faelle
- [x] MigrateTimerToMarker GESTRICHEN - `MapEjbAnnotationsToMarkers` reicht
- [x] Tests aktualisiert und bestanden (284/284)
- [x] `MigrateScheduleToTaskScheduler` implementiert (Codex APPROVED)
- [x] `MigrateScheduleToQuartz` implementiert
- [x] `MigrateEjbProgrammaticTimers` implementiert (TimerService → TaskScheduler)
- [x] `MigrateTimerServiceToQuartz` implementiert (TimerService → Quartz, Codex APPROVED)
- [x] `GenerateMigrationReport` erweitert: scannt alle 38 Ejb* Marker (Codex APPROVED)
- [x] Projektsteuerung via `project.yaml` (`migration.timer.strategy`) implementiert

## Verfuegbare Recipes

| Recipe | Beschreibung | Strategy |
|--------|--------------|----------|
| `MigrateScheduleToScheduled` | @Schedule → @Scheduled (sichere Faelle) | scheduled |
| `MigrateScheduleToTaskScheduler` | @Schedule → TaskScheduler (mit Timer-Parameter) | taskscheduler |
| `MigrateScheduleToQuartz` | @Schedule → Quartz Job | quartz |
| `MigrateEjbProgrammaticTimers` | TimerService.create* → TaskScheduler | taskscheduler |
| `MigrateTimerServiceToQuartz` | TimerService.create* → Quartz | quartz |
| `MapEjbAnnotationsToMarkers` | Fallback: @Schedule → @EjbSchedule | (alle) |
| `GenerateMigrationReport` | Erstellt MIGRATION-REVIEW.md mit allen @NeedsReview und @Ejb* Markern | - |

---
Erstellt: 2026-01-19
Aktualisiert: 2026-01-20
