# Manual Migration Profile

## Übersicht

Klassen, die nicht automatisch migriert werden können (JSF, WebSocket, Jakarta Batch), werden mit:
1. `@NeedsReview(category = MANUAL_MIGRATION)` markiert
2. `@Profile("manual-migration")` deaktiviert

Dadurch startet die Spring Boot Anwendung **ohne Compile/Runtime-Fehler**, auch wenn manuelle Nacharbeit noch aussteht.

## Betroffene Technologien

| Technologie | Grund | Alternative in Spring |
|-------------|-------|----------------------|
| JSF (`@FacesConfig`, `@ManagedBean`) | Unterschiedliches Web-Framework | Spring MVC + Thymeleaf oder JoinFaces |
| WebSocket (`@ServerEndpoint`) | Andere API | Spring WebSocket (`@ServerEndpoint` oder Reactive) |
| Jakarta Batch (`@BatchStep`, `@JobScoped`) | Andere Abstraktion | Spring Batch |

## Verhalten nach Migration

### Standard-Start (Profile NICHT aktiv)

```bash
mvn spring-boot:run
```

- ✅ Anwendung startet
- ✅ Migrierte Code funktioniert
- ⚠️ JSF/WebSocket/Batch-Klassen sind **deaktiviert** (nicht im ApplicationContext)
- ⚠️ Features dieser Klassen sind **nicht verfügbar**

### Mit Manual-Migration Profile

```bash
mvn spring-boot:run -Dspring.profiles.active=manual-migration
```

- ⚠️ Klassen werden geladen
- ❌ Wahrscheinlich Compile/Runtime-Fehler (wenn noch nicht migriert)
- 🔧 Zum Testen der manuellen Migration verwenden

## Workflow für manuelle Nacharbeit

### 1. Migration identifizieren

```bash
# Alle @NeedsReview(MANUAL_MIGRATION) finden
grep -r "MANUAL_MIGRATION" src/main/java --include="*.java"
```

### 2. MIGRATION-REVIEW.md prüfen

Nach Ausführung von `MigrationReportOnly` enthält `MIGRATION-REVIEW.md` alle offenen Punkte.

### 3. Klasse manuell migrieren

Beispiel JSF → Spring MVC:

**Vorher (JSF):**
```java
@Profile("manual-migration")  // Deaktiviert
@NeedsReview(category = MANUAL_MIGRATION)
@ManagedBean
@RequestScoped
public class TrackingController { ... }
```

**Nachher (Spring MVC):**
```java
@Controller
@RequestMapping("/tracking")
public class TrackingController { ... }
```

### 4. Profile-Annotation entfernen

Nach erfolgreicher Migration:
1. `@Profile("manual-migration")` entfernen
2. `@NeedsReview` entfernen
3. Anwendung ohne Profile starten und testen

### 5. Fertig

Klasse ist jetzt Teil des normalen ApplicationContext.

## Konfiguration in application.properties

### Vollständig migriert (empfohlen)

```properties
# Keine special Konfiguration nötig
# Alle migrierten Klassen sind aktiv
```

### Während Übergangsphase (für Tests)

```properties
# Manual-Migration Profile aktivieren
spring.profiles.active=manual-migration

# WARNUNG: Kann zu Runtime-Fehlern führen!
```

### Alternative: Globale Deaktivierung

Falls Scheduling-Probleme auftreten:

```properties
# Scheduling global deaktivieren (Fallback)
spring.task.scheduling.enabled=false
```

## Prüfung ob Profile funktioniert

```java
// In Tests:
@SpringBootTest
class ManualMigrationTest {

    @Autowired(required = false)
    private JsfController jsfController;  // Sollte null sein ohne Profile

    @Test
    void jsfControllerNotLoadedByDefault() {
        assertNull(jsfController);
    }
}
```

## Technische Details

### Rezept: AddProfileToManualMigrationClasses

Das Rezept `AddProfileToManualMigrationClasses` (Teil von `MarkManualMigrations`):
1. Findet alle Klassen mit `@NeedsReview(category = MANUAL_MIGRATION)`
2. Fügt `@Profile("manual-migration")` hinzu (wenn noch nicht vorhanden)
3. Fügt `@Profile` Import hinzu

### Rezept-Reihenfolge

```yaml
# Phase 2.5: MarkManualMigrations
recipeList:
  - de.example.rewrite.ejb.MarkJakartaBatchForMigration  # Fügt @NeedsReview hinzu
  - de.example.rewrite.ejb.MarkWebSocketForMigration     # Fügt @NeedsReview hinzu
  - de.example.rewrite.ejb.MarkJsfForMigration           # Fügt @NeedsReview hinzu
  - de.example.rewrite.ejb.AddProfileToManualMigrationClasses  # Fügt @Profile hinzu
```
