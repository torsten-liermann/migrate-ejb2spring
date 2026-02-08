# Project configuration YAML (reference)
Status: Implemented (options) and continuously expanded.
## Initial situation
Several recipes use fixed source root paths. This limits the applicability for different project structures (e.g. Gradle TestFixtures, integration tests, Kotlin source sets).
Example:
```java
if (!sourcePath.contains("src/test/")) {
    return cd;
}
```
## Affected recipes (hardcoded paths)
| Recipe | Locations | Priority |
|---|---|---|
| AddNeedsReviewAnnotation | 62-66, 75-82, 476, 492 | high |
| MigrateDataSourceDefinition | 41-48, 339, 342, 401, 852, 853 | high |
| MigrateTestStubsToTestConfiguration | 195, 275, 278, 280, 282, 592 | high |
| AddTestcontainersDevApplication | 210, 220, 416, 420 | high |
| MigrateJmsDestination | 86, 123, 230, 245 | high |
| RemoveComponentFromTestStubs | 83 | high |
| AddSpringBootApplication | 109 | high |
| GenerateMigrationReport | 31 | high |
| MigratePersistenceXmlToProperties | 42 | high |
| MigrateJndiStringToValue | 83 | high |
| MarkJsfForMigration | 31, 32 | medium |
## project.yaml (filename)
A project-wide `project.yaml` enables the central definition of the source roots and other migration-relevant parameters.
Example:
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
  strategy: keep-jaxrs    # or: migrate-to-spring-mvc
  server:
    provider: jersey      # jersey | resteasy | cxf
basePath: /api # Base path for JAX-RS endpoints (default: /api)
  client:
    strategy: keep-jaxrs  # or: manual | migrate-restclient | migrate-webclient
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

## JAX-RS Strategy (Implemented)
The JAX‑RS strategies are read in `project.yaml`. If the file is missing, `keep-jaxrs` applies to both server and client.
### Servers
- `keep-jaxrs`: JAX-RS server annotations remain unchanged.
- `migrate-to-spring-mvc`: Server migration to Spring MVC (resources, HTTP methods, parameter binding, response).
### Server provider
The server-side provider is controlled via `jaxrs.server.provider`. If the information is missing, the provider is derived from existing dependencies; If there are no provider dependencies, Jersey is considered the default.
| Providers | Spring integration | Dependency |
|----------|--------------------|------------|
| `jersey` (default) | Spring Boot Starter | `spring-boot-starter-jersey:3.4.1` |
| `resteasy` | Servlet based | `resteasy-servlet-spring-boot-starter:6.3.0.Final` |
| `cxf` | Spring Boot Starter | `cxf-spring-boot-starter-jaxrs:4.1.4` |
### Server basePath
The `jaxrs.server.basePath` defines the base path mapping for all JAX-RS endpoints.
- **Default:** `/api`
- **Example:** With `basePath: /rest`, endpoints under `/rest/{ResourcePath}` are reachable
- **Implementation per provider:**
  - **Jersey:** `@ApplicationPath("/api")` on the generated `ResourceConfig` subclass
  - **RESTEasy:** `@ApplicationPath("/api")` on the `Application` subclass
  - **CXF:** `factory.setAddress("/api")` in `JAXRSServerFactoryBean`
### Provider auto detection
If no explicit provider is configured in `project.yaml`, the provider is automatically detected from the Maven dependencies:
1. **Effective Maven model:** MavenResolutionResult is queried (compile/runtime scope)
2. **Fallback:** String-based search in POM content
3. **Multiple providers:** In case of ambiguity, a `@NeedsReview` comment is generated in the POM and no automatic generation is carried out
**Example ambiguity comment:**
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
### Recipe: GenerateJaxRsServerConfig
The recipe `com.github.rewrite.ejb.GenerateJaxRsServerConfig` generates the provider-specific Spring Boot configuration:
| option | Description |
|--------|-------------|
| **Input** | `@Path` annotated classes in module |
| **Output** | Configuration class + provider dependency |
| **Requirement** | `jaxrs.strategy: keep-jaxrs` (default) |
**Generated classes per provider:**
- **Jersey:** `JerseyConfiguration extends ResourceConfig` with `@Component` + `@ApplicationPath`
- **RESTEasy:** `ResteasyConfiguration extends Application` with `@ApplicationPath`
- **CXF:** `CxfJaxrsConfiguration` with `@Configuration` + `@Bean JAXRSServerFactoryBean`
**Existing Config Detection:**
The recipe detects existing configurations and skips generation:
- Classes with names like `*JerseyConfig*`, `*ResteasyConfig*`, `*CxfConfig*`
- Classes with `@Configuration` that use `ResourceConfig` or `JAXRSServerFactoryBean`
- Classes that extend `jakarta.ws.rs.core.Application` or `javax.ws.rs.core.Application`
- Classes with `@ApplicationPath` annotation
## JAX-WS Strategy (Implemented)
JAX-WS is controlled via `jaxws.*`. The aim is to maintain the provider; CXF is the default.
### Providers
| Providers | Description |
|----------|-------------|
| `cxf` (default) | JAX-WS Endpoints are retained, CXF Spring Boot Starter is integrated |
| `manual` | No automatic processing; Endpoints remain unchanged for manual migration |
### basePath
The `jaxws.basePath` defines the CXF Servlet mapping (`cxf.path` property).
- **Default:** `/services`
- **Example:** With `basePath: /ws`, endpoints are reachable under `/ws/{ServiceName}`
- **Note:** `cxf.path` property is only written if basePath differs from the default and is not already configured
### Examples
**CXF strategy (default):**
```yaml
# No configuration -> provider=cxf, basePath=/services
# Generiert: JaxwsConfiguration.java, cxf-spring-boot-starter-jaxws Dependency
# (cxf.path property only for non-default basePath)
```

**Explicit configuration:**
```yaml
jaxws:
  provider: cxf
  basePath: /ws
```
**Manual migration:**
```yaml
jaxws:
provider: manual # -> No automatic processing; completely manual migration
```

### Conflict handling
- **Spring-WS present:** If `spring-ws-core` or `spring-boot-starter-web-services` is present in the effective Maven model, a POM comment marker (`@NeedsReview: JAX-WS Migration Conflict`) is inserted; no CXF config generated
- **Existing CXF Config:** Existing `Endpoint` beans or `JAXRSServerFactoryBean` are recognized; no double config generation
See also: `docs/chapters/_21a-jaxws.adoc` for complete documentation.
### Client
- `keep-jaxrs`: JAX-RS client remains, provider dependencies are added.
- `manual`: JAX‑RS‑client usage is marked (`@NeedsReview` + `@Profile("manual-migration")`).
- `migrate-restclient` and `migrate-webclient`: currently not implemented; the run falls back to `manual` and generates a warning.
## Timer migration configuration (Implemented)
The timer strategy is controlled via `migration.timer.*`.
**Default (if `project.yaml` is missing):**
- `strategy = scheduled`
- `cluster = none`
Reason: conservative standard, only safe `@Schedule` cases are automatically migrated; complex timers remain as markers.
### Strategies
| Strategy | Description |
|----------|-------------|
| `scheduled` | @Schedule -> @Scheduled for simple cases, markers for complex |
| `taskscheduler` | @Schedule -> TaskScheduler pattern for timer parameters |
| `quartz` | @Schedule -> Quartz job/trigger for persistence and cluster |
### Requirements
| Setting | Effect |
|-------------|---------|
| `cluster: quartz-jdbc` | Requires `strategy: quartz` |
| `cluster: shedlock` | Not compatible with `strategy: quartz` |
| `cluster: none` | No restriction |
### Decision logic and priority
The effective strategy is validated by `getEffectiveTimerStrategy()`. Incompatible combinations throw a `ConfigurationException`.
```java
if (cluster == QUARTZ_JDBC && strategy != QUARTZ) {
    throw new ConfigurationException(...);
}
if (cluster == SHEDLOCK && strategy == QUARTZ) {
    throw new ConfigurationException(...);
}
return strategy;
```

### Examples
**Project without special requirements (default):**
```yaml
# No configuration -> strategy=scheduled, cluster=none
```
**Project with persistent timers:**
```yaml
migration:
  timer:
cluster: quartz-jdbc # -> strategy must be quartz
```

**Project with explicit Quartz choice:**
```yaml
migration:
  timer:
    strategy: quartz
    cluster: quartz-jdbc
```
## Jakarta Batch Migration (Implemented)
Jakarta Batch (JSR-352) classes are automatically marked and Spring Batch configuration stubs are generated.
**Behave:**
1. `MarkJakartaBatchForMigration` marks all Jakarta Batch classes with `@NeedsReview(category = MANUAL_MIGRATION)`
2. `GenerateSpringBatchConfigFromJsl` generates `BatchJobsConfiguration` from JSL Job XML
**Automatically detected Jakarta Batch APIs:**
- `AbstractItemReader`, `AbstractItemWriter`, `ItemReader`, `ItemWriter`, `ItemProcessor`
- `Batchlet`, `AbstractBatchlet`
- `JobOperator`, `BatchRuntime`, `JobContext`, `StepContext`
- All listener interfaces (`JobListener`, `StepListener`, `ChunkListener`, etc.)
- Partition APIs (`PartitionMapper`, `PartitionReducer`, etc.)
**JSL XML processing:**
- Scans `META-INF/batch-jobs/*.xml`
- Generates `BatchJobsConfiguration` with job/step bean definitions
- Chunk steps -> `StepBuilder.<Object, Object>chunk(...)`
- Batchlet steps -> `StepBuilder.tasklet(...)`
- Complex flows (split, flow, decision, transitions) -> TODO comments
**No configuration required** - runs automatically in the main pipeline.
## Remote interface migration configuration (Implemented)
The @Remote interface strategy is controlled via `migration.remote.*`.
**Default (if `project.yaml` is missing):**
- `strategy = rest`
### Strategies
| Strategy | Description |
|----------|-------------|
| `rest` | Generates REST Controllers, DTOs, @HttpExchange Client and Config |
| `manual` | Mark @Remote interfaces with @NeedsReview for manual migration |
### Examples
**REST strategy (default):**
```yaml
# No configuration -> strategy=rest
# Generiert: {Interface}RestController, {Method}Request DTOs, {Interface}Client, {Interface}ClientConfig
```

**Manual migration:**
```yaml
migration:
  remote:
strategy: manual   # -> @Remote removed, @NeedsReview(category=REMOTE_ACCESS) added
```
### Client properties
A base URL property is required for each generated client:
```properties
# Format: {interfaceName}.baseUrl (camelCase)
searchService.baseUrl=http://localhost:8080
paymentGateway.baseUrl=http://payments.example.com:8080
```
### Overloaded methods
Overloaded methods generate path suffixes and numbered DTO names:
- Paths: `/api/{Interface}/{method}/1`, `/api/{Interface}/{method}/2`, ...
- DTOs: `{Method}1Request`, `{Method}2Request`, ...
**Important:** Numbering is based on source file order. Method reordering changes the paths.
See also: `docs/chapters/_15-remote-local.adoc` for complete documentation.
## JSF migration configuration (Implemented)
The JSF runtime strategy is controlled via `migration.jsf.*`.
**Default (if `project.yaml` is missing):**
- `runtime = joinfaces`
### Strategies
| Strategy | Description |
|----------|-------------|
| `joinfaces` | JSF scopes remain, JoinFaces provides runtime. Marker: `CONFIGURATION` |
| `manual` | JSF scopes are marked for manual Spring migration. Marker: `MANUAL_MIGRATION` |
### Examples
**JoinFaces strategy (default):**
```yaml
# No configuration -> runtime=joinfaces
# @ViewScoped and @FlowScoped remain unchanged
# Classes are marked with @NeedsReview(category=CONFIGURATION)
```

**Manual migration:**
```yaml
migration:
  jsf:
runtime: manual   # -> @NeedsReview(category=MANUAL_MIGRATION) for Spring scope migration
```
### ConversationScoped
`@ConversationScoped` is always marked with `@NeedsReview(category=SEMANTIC_CHANGE)` regardless of the strategy because there is no direct Spring equivalent.
See also: `docs/chapters/_38-jsf.adoc` for complete documentation.
## Implementation steps (concept)
- Reading the YAML configuration (e.g. Jackson/SnakeYAML)
- Deployment in `ExecutionContext`
- Replacing hard paths with configurable values
- Defaults for missing configuration
## Alternative: OpenRewrite Styles
OpenRewrite offers styles as a project configuration. It must be checked whether styles are sufficient for the source root configuration or whether a separate YAML remains necessary.
## References
- https://docs.openrewrite.org/concepts-explanations/styles
- https://docs.gradle.org/current/userguide/java_plugin.html#source_sets
