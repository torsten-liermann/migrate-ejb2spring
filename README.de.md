# OpenRewrite Migration Suite: EJB 3.x zu Spring Boot

Automatisierte Migration von Enterprise JavaBeans (EJB) 3.x zu Spring Boot mittels [OpenRewrite](https://docs.openrewrite.org/).

## Projektstatus und Umsetzungsablauf

Die Implementierung wurde in einem kollaborativen CLI-Ablauf erstellt:
- Claude Code CLI (Opus 4.6) übernimmt die Implementierung.
- Codex CLI (ChatGPT Codex 5.3) übernimmt das Review-Gate.

Die Dokumentation unter `docs/chapters` ist die Spezifikationsgrundlage für die Umsetzung.

Der standardisierte Ablauf ist:
1. Spezifikation in `docs/chapters` erweitern.
2. Anforderungen mit Claude Code implementieren.
3. Review-Zyklen mit Codex durchführen, bis der Status `APPROVED` erreicht ist.
4. Low-Risk-Nice-to-have-Punkte direkt umsetzen, sofern sie zur Spezifikation passen.

## Quickstart

```bash
# Repository klonen
git clone https://github.com/torsten-liermann/migrate-ejb2spring.git
cd migrate-ejb2spring

# Recipes bauen und testen
./mvnw clean install

# Migration auf ejb-demo ausführen
# Hinweis: Bei Wiederholung zuerst zurücksetzen:
# git checkout ejb-demo/ && git clean -fd ejb-demo/
./mvnw -f migrate validate
```

## Voraussetzungen

- Java 17+
- Maven 3.8+
- OpenRewrite 8.x

## Repository-Struktur

| Modul | Beschreibung |
|-------|--------------|
| `ejb-demo/` | EJB 3.x Demo-Code (Before-State) |
| `ejb-to-spring-recipes/` | OpenRewrite Recipes + Tests |
| `migrate/` | Migration-Runner |
| `docs/` | Ausführliche Migrationsreferenz |

## Migrationsumfang

### Implementierte Transformationen

| EJB Feature | Spring Äquivalent | Status |
|-------------|-------------------|--------|
| `@Stateless` | `@Service` | Implementiert |
| `@Singleton` | `@Service` | Implementiert |
| `@EJB` | `@Autowired` | Implementiert |
| `@Inject` | konfigurierbar | Implementiert |
| `@TransactionAttribute` | `@Transactional` | Implementiert |
| `@MessageDriven` | `@JmsListener` | Implementiert |
| `@Local` | entfernt | Implementiert |
| `@Remote` | REST-Controller + `@HttpExchange` (konfigurierbar: rest/manual) | Implementiert |
| `@Schedule` | `@Scheduled` / Quartz | Implementiert |
| `@ApplicationException` | `@Transactional(rollbackFor)` | Implementiert |
| JSF / JoinFaces | konfigurierbar | Implementiert |
| JAX-RS Server/Client | konfigurierbar | Implementiert |
| JAX-WS (CXF) | konfigurierbar | Implementiert |

## Konfiguration (project.yaml)

Die Migration wird über eine optionale `project.yaml` im Projektwurzelverzeichnis gesteuert:

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
    provider: jersey        # jersey | resteasy | cxf (weglassen für auto-detect)
    basePath: /api
  client:
    strategy: keep-jaxrs    # keep-jaxrs | manual | migrate-restclient | migrate-webclient
    provider: jersey        # jersey | resteasy | cxf

# JAX-WS
jaxws:
  provider: cxf             # cxf | manual
  basePath: /services
```

Ohne `project.yaml` werden Maven-Standardpfade und konservative Strategien verwendet.

### Strategie-Übersicht

| Bereich | Standard | Beschreibung |
|---------|----------|--------------|
| Timer | `scheduled` | Einfache `@Schedule` zu `@Scheduled`, komplexe Fälle markiert |
| Inject | `keep-jsr330` | `@Named`/`@Inject` behalten, jakarta.inject-api Dependency |
| Remote | `rest` | REST-Controller + `@HttpExchange` Client generieren |
| JSF | `joinfaces` | JSF-Scopes behalten, JoinFaces-Runtime |
| JAX-RS | `keep-jaxrs` | JAX-RS-Annotations behalten, Jersey/RESTEasy Runtime |

## Typische Workflows

### Vollständige Migration

```bash
./mvnw -f migrate validate
```

### Einzelne Recipes testen

```bash
./mvnw org.openrewrite.maven:rewrite-maven-plugin:run \
  -Drewrite.recipeArtifactCoordinates=com.github.rewrite:ejb-to-spring-recipes:1.0.0-SNAPSHOT \
  -Drewrite.activeRecipes=com.github.rewrite.ejb.MigrateStatelessToService \
  -pl ejb-demo
```

### Tests ausführen

```bash
./mvnw test -pl ejb-to-spring-recipes
```

## Ausführliche Dokumentation

- **Vollständige Migrationsreferenz**: [`docs/ejb-to-spring-reference.adoc`](docs/ejb-to-spring-reference.adoc)
- **Konzeptmapping EJB zu Spring**: Kapitel 8-12 der Migrationsreferenz

## Lizenz

Apache License 2.0 - siehe [LICENSE](LICENSE)

Dieses Projekt enthält Code aus dem [Spring Boot Migrator](https://github.com/spring-projects-experimental/spring-boot-migrator) Projekt - siehe [NOTICE](NOTICE) für Details.
