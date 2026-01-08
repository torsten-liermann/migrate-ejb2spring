# Reproduzierbarer Migration-Run

Dieses Dokument beschreibt einen reproduzierbaren Migration-Run auf dem `ejb-demo`-Modul.

## Voraussetzungen

- Java 17+
- Maven 3.8+
- Repository ausgecheckt (sauberer Zustand)

## Referenzkommando

```bash
./mvnw -f migrate validate
```

Dieses Kommando führt drei Schritte aus (siehe `migrate/pom.xml`):

1. **install-recipes**: Installiert `ejb-to-spring-recipes` ins lokale Maven-Repository
2. **migrate-ejb-to-spring**: Führt `MigrateEjbToSpring` auf `ejb-demo` aus
3. **verify-migration**: Kompiliert `ejb-demo` zur Validierung

## Erwartete Ausgaben

### Schritt 1: Recipe-Installation

```
[INFO] --- install:3.1.3:install (default-install) @ ejb-to-spring-recipes ---
[INFO] Installing .../ejb-to-spring-recipes-1.0.0-SNAPSHOT.jar
[INFO] BUILD SUCCESS
```

### Schritt 2: Migration

```
[INFO] --- rewrite:5.48.0:run (default-cli) @ ejb-demo ---
[INFO] Validating active recipes...
[INFO] Running recipe(s)...
[INFO] Changes have been made to ejb-demo/src/main/java/...
[INFO] Please review and commit the results.
[INFO] BUILD SUCCESS
```

### Schritt 3: Kompilierung

```
[INFO] --- compiler:3.13.0:compile (default-compile) @ ejb-demo ---
[INFO] Changes detected - recompiling the module!
[INFO] BUILD SUCCESS
```

## Transformierte Dateien

| Ausgangsdatei | Transformationen |
|--------------|------------------|
| `stateless/CalculatorService.java` | `@Stateless` → `@Service` |
| `stateless/UserService.java` | `@Stateless` → `@Service`, `@EJB` → `@Autowired` |
| `singleton/ConfigurationService.java` | `@Singleton` → `@Service`, `@Startup`/`@Lock` entfernt |
| `singleton/CacheService.java` | `@Singleton` → `@Service`, `@ConcurrencyManagement` entfernt |
| `tx/PaymentService.java` | `@TransactionAttribute` → `@Transactional` |
| `cdi/InjectExample.java` | `@Inject` Behandlung (je nach `inject.strategy`) |

## Beispiel: Before/After

### Vor der Migration (EJB)

```java
@Stateless
public class CalculatorService {
    @EJB
    private AuditService auditService;

    @TransactionAttribute(TransactionAttributeType.REQUIRED)
    public int add(int a, int b) {
        return a + b;
    }
}
```

### Nach der Migration (Spring)

```java
@Service
public class CalculatorService {
    @Autowired
    private AuditService auditService;

    @Transactional(propagation = Propagation.REQUIRED)
    public int add(int a, int b) {
        return a + b;
    }
}
```

## Validierung

Nach erfolgreicher Migration:

```bash
# Kompilierung prüfen
./mvnw -pl ejb-demo compile

# Tests ausführen (falls vorhanden)
./mvnw -pl ejb-demo test
```

## Zurücksetzen

Um zum Ausgangszustand zurückzukehren:

```bash
git checkout ejb-demo/
```

## Alternativer Run: Einzelne Recipes

```bash
# Nur @Stateless migrieren
./mvnw org.openrewrite.maven:rewrite-maven-plugin:run \
  -Drewrite.recipeArtifactCoordinates=com.github.rewrite:ejb-to-spring-recipes:1.0.0-SNAPSHOT \
  -Drewrite.activeRecipes=com.github.rewrite.ejb.MigrateStatelessToService \
  -pl ejb-demo

# Nur Transaktionen migrieren
./mvnw org.openrewrite.maven:rewrite-maven-plugin:run \
  -Drewrite.recipeArtifactCoordinates=com.github.rewrite:ejb-to-spring-recipes:1.0.0-SNAPSHOT \
  -Drewrite.activeRecipes=com.github.rewrite.ejb.MigrateTransactionAttribute \
  -pl ejb-demo
```
