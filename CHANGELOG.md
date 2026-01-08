# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0] - 2026-01-26

### Added

#### Core EJB Migrations
- `MigrateStatelessToService`: `@Stateless` → `@Service`
- `MigrateSingletonToComponent`: `@Singleton` → `@Service` (removes `@Lock`, `@ConcurrencyManagement`, `@Startup`)
- `MigrateEjbToAutowired`: `@EJB` → `@Autowired`
- `MigrateInjectToAutowired`: Injection strategy - keep JSR-330 (`@Inject`/`@Named` with `jakarta.inject-api`) or migrate to Spring (`@Autowired`/`@Component`) via `inject.strategy`
- `MigrateTransactionAttribute`: `@TransactionAttribute` → `@Transactional`
- `RemoveLocalAnnotation`: Removes `@Local` annotations
- `MigrateRemoteInterfaces`: Remote EJB migration with strategy routing - REST controller + HttpExchange client (default `rest`) or `@NeedsReview` markers (`manual`) via `project.yaml`

#### Timer and Scheduling
- `MigrateScheduleToScheduled`: `@Schedule` → `@Scheduled`
- `MigrateTimerServiceToQuartz`: Timer API → Quartz (only when `timer.strategy=quartz`)
- Cluster mode validation: `quartz-jdbc` requires `timer.strategy=quartz`; `shedlock` mode compatible with `scheduled`/`taskscheduler` (ShedLock dependencies are manual)

#### Message-Driven Beans
- `MigrateMessageDrivenToJmsListener`: `@MessageDriven` → `@Component` + `@JmsListener`
- JMS provider configuration: `artemis`, `activemq`, `embedded`

#### JAX-RS Support
- `keep-jaxrs` strategy: Keep annotations, add runtime dependencies
- `migrate-to-spring-mvc` strategy: Migrate to Spring MVC (experimental)
- Client strategies: `keep-jaxrs`, `manual`, `migrate-restclient`, `migrate-webclient`

#### JAX-WS Support
- `GenerateCxfJaxwsConfig`: CXF Spring Boot integration
- Endpoint servlet mapping generation

#### JSF / JoinFaces
- `joinfaces` strategy: Keep JSF scopes with JoinFaces runtime
- JSF resource migration to META-INF

#### Other Migrations
- `@ApplicationException` → `@Transactional(rollbackFor=...)`
- CDI qualifier to Spring qualifier migration
- Arquillian to Spring Boot Test migration

#### Configuration
- `project.yaml` support for migration strategies
- Module-level configuration override
- Strategy compatibility validation (timer/cluster)

#### Documentation
- README (German + English)
- Core documentation in English (`docs/ejb-to-spring-core.en.adoc`)
- Detailed migration reference (`docs/ejb-to-spring-reference.adoc`)
- Reproducible migration run documentation

### Technical Details

- OpenRewrite 8.x based recipes
- Java 17+ required
- Maven 3.8+ required
- 105 recipe test classes
- Full integration with OpenRewrite plugin

[1.0.0]: https://github.com/torsten-liermann/migrate-ejb2spring/releases/tag/v1.0.0
