# OpenRewrite Migration Suite: EJB 3.x to Spring Boot

Automated migration of Enterprise JavaBeans (EJB) 3.x to Spring Boot using [OpenRewrite](https://docs.openrewrite.org/).

## Quickstart

```bash
# Clone repository
git clone https://github.com/torsten-liermann/migrate-ejb2spring.git
cd migrate-ejb2spring

# Build and test recipes
./mvnw clean install

# Run migration on ejb-demo
./mvnw -f migrate validate
```

## Prerequisites

- Java 17+
- Maven 3.8+
- OpenRewrite 8.x

## Repository Structure

| Module | Description |
|--------|-------------|
| `ejb-demo/` | EJB 3.x demo code (before state) |
| `ejb-to-spring-recipes/` | OpenRewrite recipes + tests |
| `migrate/` | Migration runner |
| `docs/` | Detailed migration reference |

## Migration Scope

### Implemented Transformations

| EJB Feature | Spring Equivalent | Status |
|-------------|-------------------|--------|
| `@Stateless` | `@Service` | Implemented |
| `@Singleton` | `@Service` | Implemented |
| `@EJB` | `@Autowired` | Implemented |
| `@Inject` | configurable | Implemented |
| `@TransactionAttribute` | `@Transactional` | Implemented |
| `@MessageDriven` | `@JmsListener` | Implemented |
| `@Local` | removed | Implemented |
| `@Remote` | REST controller + `@HttpExchange` (configurable: rest/manual) | Implemented |
| `@Schedule` | `@Scheduled` / Quartz | Implemented |
| `@ApplicationException` | `@Transactional(rollbackFor)` | Implemented |
| JSF / JoinFaces | configurable | Implemented |
| JAX-RS Server/Client | configurable | Implemented |
| JAX-WS (CXF) | configurable | Implemented |

## Configuration (project.yaml)

Migration is controlled via an optional `project.yaml` in the project root directory:

```yaml
migration:
  # Timer/Scheduling
  timer:
    strategy: scheduled     # scheduled | taskscheduler | quartz
    cluster: none           # none | quartz-jdbc | shedlock

  # Message-Driven Beans
  jms:
    provider: artemis       # none | artemis | activemq | embedded

  # Dependency Injection
  inject:
    strategy: keep-jsr330   # keep-jsr330 | migrate-to-spring

  # Remote EJBs
  remote:
    strategy: rest          # rest | manual

  # JavaServer Faces
  jsf:
    runtime: joinfaces      # joinfaces | manual

  # Security
  security:
    strategy: keep-jakarta  # keep-jakarta | spring-security

# JAX-RS
jaxrs:
  strategy: keep-jaxrs      # keep-jaxrs | migrate-to-spring-mvc
  server:
    provider: jersey        # jersey | resteasy | cxf (omit for auto-detect)
    basePath: /api
  client:
    strategy: keep-jaxrs    # keep-jaxrs | manual | migrate-restclient | migrate-webclient
    provider: jersey        # jersey | resteasy | cxf

# JAX-WS
jaxws:
  provider: cxf             # cxf | manual
  basePath: /services
```

Without `project.yaml`, Maven default paths and conservative strategies are used.

### Strategy Overview

| Area | Default | Description |
|------|---------|-------------|
| Timer | `scheduled` | Simple `@Schedule` to `@Scheduled`, complex cases marked |
| Inject | `keep-jsr330` | Keep `@Named`/`@Inject`, add jakarta.inject-api dependency |
| Remote | `rest` | Generate REST controller + `@HttpExchange` client |
| JSF | `joinfaces` | Keep JSF scopes, JoinFaces runtime |
| JAX-RS | `keep-jaxrs` | Keep JAX-RS annotations, Jersey/RESTEasy runtime |

## Typical Workflows

### Full Migration

```bash
./mvnw -f migrate validate
```

### Test Individual Recipes

```bash
./mvnw org.openrewrite.maven:rewrite-maven-plugin:run \
  -Drewrite.recipeArtifactCoordinates=com.github.rewrite:ejb-to-spring-recipes:1.0.0-SNAPSHOT \
  -Drewrite.activeRecipes=com.github.rewrite.ejb.MigrateStatelessToService \
  -pl ejb-demo
```

### Run Tests

```bash
./mvnw test -pl ejb-to-spring-recipes
```

## Detailed Documentation

- **Complete Migration Reference**: [`docs/ejb-to-spring-reference.adoc`](docs/ejb-to-spring-reference.adoc)
- **EJB to Spring Concept Mapping**: Chapters 8-12 of the migration reference

## License

Apache License 2.0 - see [LICENSE](LICENSE)

This project includes code from the [Spring Boot Migrator](https://github.com/spring-projects-experimental/spring-boot-migrator) project - see [NOTICE](NOTICE) for details.
