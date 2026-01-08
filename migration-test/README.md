# Jakarta EE zu Spring Boot Migration Test

Dieses Modul testet OpenRewrite-Rezepte gegen die **Cargo Tracker** Referenzanwendung.

## Zielarchitektur

```
┌─────────────────────────────────────────────────────────────────┐
│  ZIEL: Spring Boot Native Application                          │
├─────────────────────────────────────────────────────────────────┤
│  ✓ Executable JAR (java -jar app.jar)                          │
│  ✓ GraalVM Native Image (./app)                                │
│  ✓ Container-Image (OCI/Docker)                                │
├─────────────────────────────────────────────────────────────────┤
│  ✗ KEIN JNDI - Properties/Environment-Variablen                │
│  ✗ KEIN Application Server - Embedded Tomcat/Netty             │
│  ✗ KEINE WAR-Deployments                                       │
└─────────────────────────────────────────────────────────────────┘
```

## Anspruch

**Maximale Automatisierung.** Jede manuelle Migration ist ein Fehler im Rezept.
Was nicht automatisierbar ist, wird explizit markiert und dokumentiert.

## Voraussetzungen

- Maven 3.9+
- Java 21+
- Cargo Tracker geklont unter `/path/to/projects/cargotracker-wildfly`

## Verwendung

### 1. Git Worktree erstellen

```bash
cd /path/to/projects/cargotracker-wildfly

# Neuen Branch für Tests erstellen (falls nicht vorhanden)
git branch migration-test 2>/dev/null || true

# Worktree erstellen
git worktree add ../cargotracker-migration-test migration-test
cd ../cargotracker-migration-test
```

**Vorteile gegenüber `cp -r`:**
- Schneller (keine Dateikopie)
- Git-Operationen bleiben möglich
- Einfaches Zurücksetzen mit `git checkout -- .`

### 2. rewrite.yml kopieren

```bash
cp ../migrate-ejb2spring/migration-test/rewrite.yml .
```

### 3. Dry-Run ausführen (zeigt was geändert würde)

```bash
# Mit Java-Rezepten (erfordert mvn install im migrate-ejb2spring Projekt)
mvn org.openrewrite.maven:rewrite-maven-plugin:dryRun \
    -Drewrite.activeRecipes=com.github.rewrite.migration.FullSpringBootMigration \
    -Drewrite.recipeArtifactCoordinates=de.example.rewrite:ejb-to-spring-recipes:1.0.0-SNAPSHOT

# Alternativ: Nur YAML-Rezepte (keine Java-Rezepte)
mvn org.openrewrite.maven:rewrite-maven-plugin:dryRun \
    -Drewrite.activeRecipes=com.github.rewrite.migration.JakartaEEToSpringBoot
```

### 3b. Patch prüfen

```bash
head -n 100 target/rewrite/rewrite.patch
```

### 4. Einzelne Rezepte testen

```bash
# Nur JAX-RS → Spring MVC
mvn org.openrewrite.maven:rewrite-maven-plugin:dryRun \
    -Drewrite.activeRecipes=com.github.rewrite.migration.JaxRsToSpringMvc

# Nur EJB → Spring Service
mvn org.openrewrite.maven:rewrite-maven-plugin:dryRun \
    -Drewrite.activeRecipes=com.github.rewrite.migration.EjbToSpringService

# Nur CDI → Spring DI
mvn org.openrewrite.maven:rewrite-maven-plugin:dryRun \
    -Drewrite.activeRecipes=com.github.rewrite.migration.CdiToSpringDi
```

### 5. Tatsächliche Migration ausführen

```bash
mvn org.openrewrite.maven:rewrite-maven-plugin:run \
    -Drewrite.activeRecipes=com.github.rewrite.migration.FullSpringBootMigration \
    -Drewrite.recipeArtifactCoordinates=de.example.rewrite:ejb-to-spring-recipes:1.0.0-SNAPSHOT
```

## Mehrphasen-Migration (WIESO / WESHALB / WARUM / WIE)

**WIESO?** Mehrere Rezepte sind `ScanningRecipe`s und sehen pro Lauf nur den
ursprünglichen AST. Alles, was im selben Lauf erzeugt wird (z.B. `@JmsListener`,
`@Scheduled`, `@NeedsReview`, `application.properties`), ist für Scanner erst in
einem zweiten Lauf sichtbar. Ein einzelnes `rewrite:run` würde daher Teile
überspringen oder unvollständig generieren.

**WESHALB?** Wir trennen reine Refactorings (Typ-/Annotation-Migration) von
Scan- und Generierungsaufgaben (Enable-Annotationen, Properties, Report), damit
jede Phase auf einem stabilen, bereits transformierten Code-Stand arbeitet.
Das reduziert Duplikate, verhindert verpasste Treffer und macht die Pipeline
idempotent.

**WARUM?** Die Reihenfolge spiegelt echte Abhängigkeiten: erst Code migrieren,
dann auf dem migrierten Code scannen und konfigurieren, dann manuelle Aufgaben
markieren und am Ende den Report erzeugen. So entsteht eine reproduzierbare
Migration mit klaren, nachvollziehbaren Restarbeiten.

**WIE?** Nutze entweder das Skript (empfohlen) oder führe die Phasen explizit
aus. Das Skript setzt den Worktree zurück, führt alle Phasen in der korrekten
Reihenfolge aus und erzeugt `MIGRATION-REVIEW.md`.

Empfohlener Lauf via Script (vom `migrate-ejb2spring` Repo aus):
```bash
MIGRATION_WORKDIR=/path/to/projects/cargotracker-migration-test \
  ./migration-test/run-migration.sh
```

Manueller Phasenlauf (gleiche Reihenfolge):
```bash
# Phase 1: Hauptmigration (Code-Refactorings)
mvn org.openrewrite.maven:rewrite-maven-plugin:run \
  -Drewrite.activeRecipes=com.github.rewrite.migration.FullSpringBootMigration \
  -Drewrite.recipeArtifactCoordinates=de.example.rewrite:ejb-to-spring-recipes:1.0.0-SNAPSHOT

# Phase 1.3: Enable-Annotationen auf Basis der neuen @JmsListener/@Scheduled
mvn org.openrewrite.maven:rewrite-maven-plugin:run \
  -Drewrite.activeRecipes=de.example.rewrite.ejb.EnableJmsAndSchedulingPhase \
  -Drewrite.recipeArtifactCoordinates=de.example.rewrite:ejb-to-spring-recipes:1.0.0-SNAPSHOT

# Phase 1.5: Properties-Migration (persistence.xml + @DataSourceDefinition)
mvn org.openrewrite.maven:rewrite-maven-plugin:run \
  -Drewrite.activeRecipes=de.example.rewrite.ejb.PropertyMigration \
  -Drewrite.recipeArtifactCoordinates=de.example.rewrite:ejb-to-spring-recipes:1.0.0-SNAPSHOT

# Phase 2: JMS-Destinations als Spring-Konfiguration
mvn org.openrewrite.maven:rewrite-maven-plugin:run \
  -Drewrite.activeRecipes=de.example.rewrite.ejb.JmsDestinationMigration \
  -Drewrite.recipeArtifactCoordinates=de.example.rewrite:ejb-to-spring-recipes:1.0.0-SNAPSHOT

# Phase 2.5: Nicht-automatisierbares markieren
mvn org.openrewrite.maven:rewrite-maven-plugin:run \
  -Drewrite.activeRecipes=de.example.rewrite.ejb.MarkManualMigrations \
  -Drewrite.recipeArtifactCoordinates=de.example.rewrite:ejb-to-spring-recipes:1.0.0-SNAPSHOT

# Phase 3: Report generieren
mvn org.openrewrite.maven:rewrite-maven-plugin:run \
  -Drewrite.activeRecipes=de.example.rewrite.ejb.MigrationReportOnly \
  -Drewrite.recipeArtifactCoordinates=de.example.rewrite:ejb-to-spring-recipes:1.0.0-SNAPSHOT
```

## Dev-Start mit Testcontainers (Spring Boot 3.5.x)

**WIESO?** Entwickler sollen die App ohne lokale DB starten können. Testcontainers
liefert die Datenbank automatisch beim Start.

**WESHALB?** Spring Boot 3.5.x unterstützt Development-time Services offiziell:
Start über den Test-Classpath, Container werden über `@ServiceConnection`
automatisch verdrahtet.

**WARUM?** Das ersetzt Docker Compose im Dev-Workflow und verhindert
"läuft-nur-auf-meiner-Kiste"-Setups.

**WIE?** Die Migration erzeugt:
- `src/test/java/.../DevContainersConfig.java` (Container-Bean mit `@ServiceConnection`)
- `src/test/java/.../DevCargoTrackerApplication.java` (Wrapper für den App-Start)
- Test-Abhängigkeiten für `spring-boot-testcontainers` und `org.testcontainers:postgresql`

Starten:
```bash
./mvnw spring-boot:test-run
```

Anpassbar in `migration-test/rewrite.yml`:
```yaml
- de.example.rewrite.ejb.AddTestcontainersDevApplication:
    applicationClassName: CargoTrackerApplication
    devApplicationClassName: DevCargoTrackerApplication
    databaseType: postgresql
    containerImage: postgres:16-alpine
```

Voraussetzung: Docker/Container-Runtime muss laufen.

### 6. Verfügbare Rezepte anzeigen

```bash
mvn org.openrewrite.maven:rewrite-maven-plugin:discover \
    -Drewrite.recipe=org.openrewrite.java
```

### Worktree zurücksetzen (für erneute Runs)

```bash
git checkout -- .
mvn clean
```

## Rezepte

| Rezept | Beschreibung |
|--------|--------------|
| `com.github.rewrite.migration.FullSpringBootMigration` | Vollständige Migration (empfohlen) |
| `com.github.rewrite.migration.JakartaEEToSpringBoot` | Nur Annotations-Ersetzung |
| `com.github.rewrite.migration.JaxRsToSpringMvc` | Nur JAX-RS Annotationen |
| `com.github.rewrite.migration.EjbToSpringService` | Nur EJB Session Beans |
| `com.github.rewrite.migration.CdiToSpringDi` | Nur CDI Injection |
| `com.github.rewrite.migration.AddSpringBootStarter` | Spring Boot Dependencies |

### Java-basierte Rezepte (M3-M5)

| Rezept | Beschreibung |
|--------|--------------|
| `de.example.rewrite.ejb.MigrateCdiEventsToSpring` | CDI Events → ApplicationEventPublisher |
| `de.example.rewrite.ejb.MigrateTransactionAttributeJakarta` | @TransactionAttribute → @Transactional |
| `de.example.rewrite.ejb.MigrateResourceLookupToAutowired` | @Resource(lookup=...) → @Autowired |
| `de.example.rewrite.ejb.MigrateCdiInstanceToObjectProvider` | Instance<T> → ObjectProvider<T> |
| `de.example.rewrite.ejb.MigrateMessageDrivenToJmsListener` | @MessageDriven → @Component + @JmsListener |
| `de.example.rewrite.ejb.MigrateScheduleToScheduled` | @Schedule → @Scheduled(cron=...) |
| `de.example.rewrite.ejb.AddSpringBootApplication` | Generiert Main-Klasse mit @SpringBootApplication |
| `de.example.rewrite.ejb.AddEnableJmsAndScheduling` | Fügt @EnableJms/@EnableScheduling hinzu |
| `de.example.rewrite.ejb.AddTestcontainersDevApplication` | Generiert Dev-Wrapper + Testcontainers-Config |
| `de.example.rewrite.ejb.AddNeedsReviewAnnotation` | Markiert Elemente mit @NeedsReview |
| `de.example.rewrite.ejb.GenerateMigrationReport` | Generiert MIGRATION-REVIEW.md Report |

## Bekannte Einschränkungen

Die meisten Migrationen sind jetzt automatisiert. Folgende Aspekte erfordern noch manuelle Prüfung:

### Automatisch migriert (M3-M5)

- ✅ **@Schedule → @Scheduled(cron=...)**: Automatische Cron-Konvertierung
- ✅ **@MessageDriven → @JmsListener**: activationConfig.destination extrahiert
- ✅ **@Observes → @EventListener**: Annotation korrekt auf Methode verschoben
- ✅ **Event<T> → ApplicationEventPublisher**: Generics entfernt, .fire() → .publishEvent()
- ✅ **@TransactionAttribute → @Transactional**: Propagation-Mapping
- ✅ **@Resource(lookup=...) → @Autowired**: Mit @NeedsReview markiert
- ✅ **Instance<T> → ObjectProvider<T>**: API-Anpassungen
- ✅ **@SpringBootApplication**: Main-Klasse wird generiert
- ✅ **@EnableJms/@EnableScheduling**: Automatisch hinzugefügt

### Nicht automatisierbar

1. **@Produces (CDI) mit InjectionPoint → @Bean**: Kein Spring-Äquivalent
2. **Jakarta Batch → Spring Batch**: Völlig unterschiedliches API
3. **WebSocket Endpoints**: Unterschiedliches Handler-Modell
4. **@Lock → ReadWriteLock**: Manuelle Implementierung (entfernt mit @NeedsReview)

### Nacharbeit (mit @NeedsReview markiert)

Alle Stellen, die manuelle Prüfung erfordern, sind mit `@NeedsReview` annotiert.
Nach der Migration: `GenerateMigrationReport` erzeugt `MIGRATION-REVIEW.md` mit allen offenen Punkten.

## Ergebnis-Analyse

Nach dem Dry-Run wird ein Report erstellt unter:
- `target/rewrite/rewrite.patch` - Git-Patch-Datei mit allen Änderungen

## Weiterentwicklung

Für komplexere Migrationen siehe:
- Spring Boot Migrator: https://github.com/spring-projects-experimental/spring-boot-migrator
- OpenRewrite Recipes: https://docs.openrewrite.org/recipes
