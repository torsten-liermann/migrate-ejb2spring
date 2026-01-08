# Projekt‑Konfigurations‑YAML (Referenz)

Status: Implementiert (Optionen) und fortlaufend erweitert.

## Ausgangslage

Mehrere Rezepte verwenden feste Source‑Root‑Pfade. Das begrenzt die Anwendbarkeit bei abweichenden Projektstrukturen (z. B. Gradle‑TestFixtures, Integration‑Tests, Kotlin‑Quellsets).

Beispiel:
```java
if (!sourcePath.contains("src/test/")) {
    return cd;
}
```

## Betroffene Rezepte (Hardcoded‑Pfade)

| Rezept | Fundstellen | Priorität |
|---|---|---|
| AddNeedsReviewAnnotation | 62–66, 75–82, 476, 492 | hoch |
| MigrateDataSourceDefinition | 41–48, 339, 342, 401, 852, 853 | hoch |
| MigrateTestStubsToTestConfiguration | 195, 275, 278, 280, 282, 592 | hoch |
| AddTestcontainersDevApplication | 210, 220, 416, 420 | hoch |
| MigrateJmsDestination | 86, 123, 230, 245 | hoch |
| RemoveComponentFromTestStubs | 83 | hoch |
| AddSpringBootApplication | 109 | hoch |
| GenerateMigrationReport | 31 | hoch |
| MigratePersistenceXmlToProperties | 42 | hoch |
| MigrateJndiStringToValue | 83 | hoch |
| MarkJsfForMigration | 31, 32 | mittel |

## project.yaml (Dateiname)

Eine projektweite `project.yaml` ermöglicht die zentrale Definition der Source‑Roots und weiterer migrationsrelevanter Parameter.

Beispiel:
```yaml
sources:
  main:
    - src/main/java
    - src/main/kotlin
  test:
    - src/test/java
    - src/test/kotlin
    - src/testFixtures/java
    - src/integrationTest/java
  resources:
    - src/main/resources
    - src/test/resources

jaxrs:
  strategy: keep-jaxrs    # oder: migrate-to-spring-mvc
  server:
    provider: jersey      # jersey | resteasy | cxf
    basePath: /api        # Base path für JAX-RS Endpoints (Default: /api)
  client:
    strategy: keep-jaxrs  # oder: manual | migrate-restclient | migrate-webclient
    provider: jersey      # jersey, resteasy, cxf
    providerVersion: "3.1.5"

jaxws:
  provider: cxf           # cxf | manual
  basePath: /services     # CXF servlet mapping (Default: /services)

test-stubs:
  enabled: true
  patterns:
    - "*Stub"
    - "*Mock"
    - "*Fake"

migration:
  timer:
    strategy: scheduled  # scheduled | taskscheduler | quartz
    cluster: none        # none | quartz-jdbc | shedlock
```

## JAX-RS-Strategie (Implementiert)

Die JAX‑RS‑Strategien werden in `project.yaml` gelesen. Fehlt die Datei, gilt `keep-jaxrs` für Server und Client.

### Server

- `keep-jaxrs`: JAX‑RS‑Server‑Annotationen bleiben unverändert.
- `migrate-to-spring-mvc`: Server‑Migration zu Spring MVC (Ressourcen, HTTP‑Methoden, Parameter‑Binding, Response).

### Server-Provider

Der serverseitige Provider wird über `jaxrs.server.provider` gesteuert. Fehlt die Angabe, wird der Provider aus bestehenden Dependencies abgeleitet; falls keine Provider‑Dependencies vorhanden sind, gilt Jersey als Default.

| Provider | Spring-Integration | Dependency |
|----------|-------------------|------------|
| `jersey` (Default) | Spring Boot Starter | `spring-boot-starter-jersey:3.4.1` |
| `resteasy` | Servlet-basiert | `resteasy-servlet-spring-boot-starter:6.3.0.Final` |
| `cxf` | Spring Boot Starter | `cxf-spring-boot-starter-jaxrs:4.1.4` |

### Server-basePath

Der `jaxrs.server.basePath` definiert das Basis-Pfad-Mapping für alle JAX-RS Endpoints.

- **Default:** `/api`
- **Beispiel:** Bei `basePath: /rest` werden Endpoints unter `/rest/{ResourcePath}` erreichbar
- **Umsetzung pro Provider:**
  - **Jersey:** `@ApplicationPath("/api")` auf der generierten `ResourceConfig`-Subklasse
  - **RESTEasy:** `@ApplicationPath("/api")` auf der `Application`-Subklasse
  - **CXF:** `factory.setAddress("/api")` im `JAXRSServerFactoryBean`

### Provider Auto-Detection

Wenn kein expliziter Provider in `project.yaml` konfiguriert ist, wird der Provider automatisch aus den Maven-Dependencies erkannt:

1. **Effektives Maven-Modell:** MavenResolutionResult wird abgefragt (compile/runtime Scope)
2. **Fallback:** String-basierte Suche im POM-Inhalt
3. **Mehrere Provider:** Bei Ambiguität wird ein `@NeedsReview` Kommentar im POM erzeugt und keine automatische Generierung durchgeführt

**Beispiel Ambiguitäts-Kommentar:**
```xml
<!--
    @NeedsReview: Multiple JAX-RS Providers Detected
    Found dependencies for: jersey, resteasy

    Auto-generation skipped due to ambiguity. Please either:
      1. Remove conflicting provider dependencies from POM
      2. Explicitly configure the desired provider in project.yaml:
         jaxrs:
           server:
             provider: jersey|resteasy|cxf
-->
```

### Rezept: GenerateJaxRsServerConfig

Das Rezept `com.github.rewrite.ejb.GenerateJaxRsServerConfig` generiert die provider-spezifische Spring Boot Konfiguration:

| Option | Beschreibung |
|--------|-------------|
| **Input** | `@Path`-annotierte Klassen im Modul |
| **Output** | Konfigurations-Klasse + Provider-Dependency |
| **Voraussetzung** | `jaxrs.strategy: keep-jaxrs` (Default) |

**Generierte Klassen pro Provider:**

- **Jersey:** `JerseyConfiguration extends ResourceConfig` mit `@Component` + `@ApplicationPath`
- **RESTEasy:** `ResteasyConfiguration extends Application` mit `@ApplicationPath`
- **CXF:** `CxfJaxrsConfiguration` mit `@Configuration` + `@Bean JAXRSServerFactoryBean`

**Existing Config Detection:**

Das Rezept erkennt vorhandene Konfigurationen und überspringt die Generierung:
- Klassen mit Namen wie `*JerseyConfig*`, `*ResteasyConfig*`, `*CxfConfig*`
- Klassen mit `@Configuration` die `ResourceConfig` oder `JAXRSServerFactoryBean` nutzen
- Klassen die `jakarta.ws.rs.core.Application` oder `javax.ws.rs.core.Application` erweitern
- Klassen mit `@ApplicationPath` Annotation

## JAX-WS-Strategie (Implementiert)

JAX-WS wird über `jaxws.*` gesteuert. Ziel ist der Provider-Erhalt; CXF ist der Default.

### Provider

| Provider | Beschreibung |
|----------|-------------|
| `cxf` (Default) | JAX-WS Endpoints bleiben erhalten, CXF Spring Boot Starter wird integriert |
| `manual` | Keine automatische Verarbeitung; Endpoints bleiben für manuelle Migration unverändert |

### basePath

Der `jaxws.basePath` definiert das CXF Servlet-Mapping (`cxf.path` Property).

- **Default:** `/services`
- **Beispiel:** Bei `basePath: /ws` werden Endpoints unter `/ws/{ServiceName}` erreichbar
- **Hinweis:** `cxf.path` Property wird nur geschrieben wenn basePath vom Default abweicht und nicht bereits konfiguriert ist

### Beispiele

**CXF-Strategie (Default):**
```yaml
# Keine Konfiguration → provider=cxf, basePath=/services
# Generiert: JaxwsConfiguration.java, cxf-spring-boot-starter-jaxws Dependency
# (cxf.path Property nur bei nicht-default basePath)
```

**Explizite Konfiguration:**
```yaml
jaxws:
  provider: cxf
  basePath: /ws
```

**Manuelle Migration:**
```yaml
jaxws:
  provider: manual   # → Keine automatische Verarbeitung; vollständig manuelle Migration
```

### Konfliktbehandlung

- **Spring-WS vorhanden:** Wenn `spring-ws-core` oder `spring-boot-starter-web-services` im effektiven Maven-Modell vorhanden ist, wird ein POM-Kommentar-Marker (`@NeedsReview: JAX-WS Migration Conflict`) eingefügt; keine CXF-Config generiert
- **Vorhandene CXF-Config:** Existierende `Endpoint`-Beans oder `JAXRSServerFactoryBean` werden erkannt; keine doppelte Config-Generierung

Siehe auch: `docs/chapters/_21a-jaxws.adoc` für vollständige Dokumentation.

### Client

- `keep-jaxrs`: JAX‑RS‑Client bleibt, Provider‑Dependencies werden hinzugefügt.
- `manual`: JAX‑RS‑Client‑Nutzung wird markiert (`@NeedsReview` + `@Profile("manual-migration")`).
- `migrate-restclient` und `migrate-webclient`: derzeit nicht implementiert; der Lauf fällt auf `manual` zurück und erzeugt eine Warnung.

## Timer-Migration Konfiguration (Implementiert)

Die Timer-Strategie wird über `migration.timer.*` gesteuert.

**Default (wenn `project.yaml` fehlt):**

- `strategy = scheduled`
- `cluster = none`

Begründung: konservativer Standard, nur sichere `@Schedule`-Fälle werden automatisiert migriert; komplexe Timer verbleiben als Marker.

### Strategien

| Strategy | Beschreibung |
|----------|-------------|
| `scheduled` | @Schedule → @Scheduled für einfache Fälle, Marker für komplexe |
| `taskscheduler` | @Schedule → TaskScheduler-Pattern für Timer-Parameter |
| `quartz` | @Schedule → Quartz Job/Trigger für Persistenz und Cluster |

### Anforderungen

| Einstellung | Wirkung |
|-------------|---------|
| `cluster: quartz-jdbc` | Erfordert `strategy: quartz` |
| `cluster: shedlock` | Nicht kompatibel mit `strategy: quartz` |
| `cluster: none` | Keine Einschränkung |

### Entscheidungslogik und Priorität

Die effektive Strategie wird durch `getEffectiveTimerStrategy()` validiert. Inkompatible Kombinationen lösen eine `ConfigurationException` aus.

```java
if (cluster == QUARTZ_JDBC && strategy != QUARTZ) {
    throw new ConfigurationException(...);
}
if (cluster == SHEDLOCK && strategy == QUARTZ) {
    throw new ConfigurationException(...);
}
return strategy;
```

### Beispiele

**Projekt ohne spezielle Anforderungen (Default):**
```yaml
# Keine Konfiguration → strategy=scheduled, cluster=none
```

**Projekt mit persistenten Timern:**
```yaml
migration:
  timer:
    cluster: quartz-jdbc   # → strategy muss quartz sein
```

**Projekt mit expliziter Quartz-Wahl:**
```yaml
migration:
  timer:
    strategy: quartz
    cluster: quartz-jdbc
```

## Jakarta Batch Migration (Implementiert)

Jakarta Batch (JSR-352) Klassen werden automatisch markiert und Spring Batch Konfigurationsstubs generiert.

**Verhalten:**

1. `MarkJakartaBatchForMigration` markiert alle Jakarta Batch Klassen mit `@NeedsReview(category = MANUAL_MIGRATION)`
2. `GenerateSpringBatchConfigFromJsl` generiert `BatchJobsConfiguration` aus JSL Job XML

**Automatisch erkannte Jakarta Batch APIs:**

- `AbstractItemReader`, `AbstractItemWriter`, `ItemReader`, `ItemWriter`, `ItemProcessor`
- `Batchlet`, `AbstractBatchlet`
- `JobOperator`, `BatchRuntime`, `JobContext`, `StepContext`
- Alle Listener-Interfaces (`JobListener`, `StepListener`, `ChunkListener`, etc.)
- Partition-APIs (`PartitionMapper`, `PartitionReducer`, etc.)

**JSL XML Verarbeitung:**

- Scannt `META-INF/batch-jobs/*.xml`
- Generiert `BatchJobsConfiguration` mit Job/Step Bean-Definitionen
- Chunk-Steps → `StepBuilder.<Object, Object>chunk(...)`
- Batchlet-Steps → `StepBuilder.tasklet(...)`
- Komplexe Flows (split, flow, decision, transitions) → TODO-Kommentare

**Keine Konfiguration erforderlich** - läuft automatisch in der Hauptpipeline.

## Remote-Interface-Migration Konfiguration (Implementiert)

Die @Remote-Interface-Strategie wird über `migration.remote.*` gesteuert.

**Default (wenn `project.yaml` fehlt):**

- `strategy = rest`

### Strategien

| Strategy | Beschreibung |
|----------|-------------|
| `rest` | Generiert REST-Controller, DTOs, @HttpExchange-Client und Config |
| `manual` | Markiert @Remote-Interfaces mit @NeedsReview für manuelle Migration |

### Beispiele

**REST-Strategie (Default):**
```yaml
# Keine Konfiguration → strategy=rest
# Generiert: {Interface}RestController, {Method}Request DTOs, {Interface}Client, {Interface}ClientConfig
```

**Manuelle Migration:**
```yaml
migration:
  remote:
    strategy: manual   # → @Remote entfernt, @NeedsReview(category=REMOTE_ACCESS) hinzugefügt
```

### Client-Properties

Für jeden generierten Client wird eine Base-URL-Property benötigt:

```properties
# Format: {interfaceName}.baseUrl (camelCase)
searchService.baseUrl=http://localhost:8080
paymentGateway.baseUrl=http://payments.example.com:8080
```

### Überladene Methoden

Bei überladenen Methoden werden Pfad-Suffixe und nummerierte DTO-Namen generiert:

- Pfade: `/api/{Interface}/{method}/1`, `/api/{Interface}/{method}/2`, ...
- DTOs: `{Method}1Request`, `{Method}2Request`, ...

**Wichtig:** Die Nummerierung basiert auf der Quelldatei-Reihenfolge. Methodenumordnung ändert die Pfade.

Siehe auch: `docs/chapters/_15-remote-local.adoc` für vollständige Dokumentation.

## JSF-Migration Konfiguration (Implementiert)

Die JSF-Runtime-Strategie wird über `migration.jsf.*` gesteuert.

**Default (wenn `project.yaml` fehlt):**

- `runtime = joinfaces`

### Strategien

| Strategy | Beschreibung |
|----------|-------------|
| `joinfaces` | JSF-Scopes bleiben, JoinFaces stellt Runtime bereit. Marker: `CONFIGURATION` |
| `manual` | JSF-Scopes werden für manuelle Spring-Migration markiert. Marker: `MANUAL_MIGRATION` |

### Beispiele

**JoinFaces-Strategie (Default):**
```yaml
# Keine Konfiguration → runtime=joinfaces
# @ViewScoped, @FlowScoped bleiben unverändert
# Klassen werden mit @NeedsReview(category=CONFIGURATION) markiert
```

**Manuelle Migration:**
```yaml
migration:
  jsf:
    runtime: manual   # → @NeedsReview(category=MANUAL_MIGRATION) für Spring-Scope-Migration
```

### ConversationScoped

`@ConversationScoped` wird unabhängig von der Strategie immer mit `@NeedsReview(category=SEMANTIC_CHANGE)` markiert, da es keine direkte Spring-Entsprechung gibt.

Siehe auch: `docs/chapters/_38-jsf.adoc` für vollständige Dokumentation.

## Umsetzungsschritte (Konzept)

- Einlesen der YAML‑Konfiguration (z. B. Jackson/SnakeYAML)
- Bereitstellung im `ExecutionContext`
- Ersetzung harter Pfade durch konfigurierbare Werte
- Defaults für fehlende Konfiguration

## Alternative: OpenRewrite Styles

OpenRewrite bietet Styles als Projektkonfiguration. Es ist zu prüfen, ob Styles für die Source‑Root‑Konfiguration ausreichend sind oder ob eine eigene YAML erforderlich bleibt.

## Referenzen

- https://docs.openrewrite.org/concepts-explanations/styles
- https://docs.gradle.org/current/userguide/java_plugin.html#source_sets
