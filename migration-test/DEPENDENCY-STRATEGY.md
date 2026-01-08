# Spring Boot Dependency Strategie

Dieses Dokument erklärt die verschiedenen Dependency-Management-Optionen für die Migration.

## Übersicht

Es gibt drei Rezepte für das Dependency-Management:

| Rezept | Verwendung | Corporate Parent |
|--------|------------|------------------|
| `AddSpringBootParent` | Neue Projekte, einfache Strukturen | **Wird ersetzt** |
| `AddSpringBootBom` | Enterprise-Projekte (empfohlen) | **Bleibt erhalten** |
| `AddSpringBootStarter` | Alias für `AddSpringBootBom` | **Bleibt erhalten** |

## Wann Parent vs. BOM verwenden?

### `AddSpringBootParent` verwenden wenn:

- ✅ Kein Corporate Parent existiert
- ✅ Einfaches Single-Module Projekt
- ✅ Volle Kontrolle über das POM gewünscht
- ✅ Keine Plugin-Versionskonflikte erwartet

**Vorteile:**
- Einfachste Konfiguration
- Versionen werden automatisch vom Parent POM verwaltet
- Keine expliziten Versionsangaben nötig

**Nachteile:**
- Ersetzt existierenden Corporate Parent
- Kann zu Konflikten mit bestehenden Plugin-Konfigurationen führen

### `AddSpringBootBom` verwenden wenn:

- ✅ Corporate Parent POM vorhanden (z.B. `corporate-parent:1.0`)
- ✅ Multi-Module Projekt mit eigenem Parent
- ✅ Bestehende Plugin-Konfigurationen beibehalten werden müssen
- ✅ Enterprise-Umgebung mit zentralem Dependency-Management

**Vorteile:**
- Corporate Parent bleibt intakt
- Versionen werden über BOM verwaltet
- Flexibler für komplexe Projekte

**Nachteile:**
- Explizite Versionsangaben für Plugins erforderlich
- Etwas mehr Konfiguration im POM

## JMS Provider Optionen

JMS-Dependencies werden **nicht automatisch** von `AddSpringBootParent`/`AddSpringBootBom` hinzugefügt (Provider-Neutralität).

**HINWEIS:** Das Repo enthält `FullSpringBootMigration` welches **für CargoTracker** konfiguriert ist und `AddJmsArtemisStarter` inkludiert. Für generische Projekte ohne JMS diese Zeile in `rewrite.yml` entfernen oder ein eigenes Rezept verwenden.

Für Projekte mit JMS (`@MessageDriven`, `@JmsListener`), eines der folgenden Rezepte verwenden:

| Rezept | JMS Provider |
|--------|--------------|
| `AddJmsArtemisStarter` | ActiveMQ Artemis (empfohlen für WildFly) |
| `AddJmsActiveMQStarter` | Classic ActiveMQ |

### Beispiel: CargoTracker (mit Artemis)

```yaml
recipeList:
  - com.github.rewrite.migration.FullSpringBootMigration
  - com.github.rewrite.migration.AddJmsArtemisStarter  # Nur wenn JMS verwendet wird
```

### Beispiel: Projekt ohne JMS

```yaml
recipeList:
  - com.github.rewrite.migration.FullSpringBootMigration
  # Kein JMS-Starter - Projekt verwendet kein JMS
```

## Migration mit BOM (Enterprise)

1. **Haupt-Migration ausführen:**
   ```bash
   mvn rewrite:run -Drewrite.activeRecipes=com.github.rewrite.migration.FullSpringBootMigration
   ```

2. **Bei JMS-Projekten zusätzlich:**
   ```bash
   mvn rewrite:run -Drewrite.activeRecipes=com.github.rewrite.migration.AddJmsArtemisStarter
   ```

## Ergebnis im POM

### Nach AddSpringBootBom:

```xml
<!-- HINWEIS: Versionen sind hardcoded weil OpenRewrite Maven-Properties
     nicht auflöst. Für Upgrades alle Versionen ändern. -->

<dependencyManagement>
  <dependencies>
    <!-- Spring Boot BOM -->
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-dependencies</artifactId>
      <version>3.5.9</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
    <!-- Logback/SLF4J explizit gepinnt (verhindert Version-Konflikte) -->
    <dependency>
      <groupId>ch.qos.logback</groupId>
      <artifactId>logback-classic</artifactId>
      <version>1.5.22</version>
    </dependency>
    <dependency>
      <groupId>ch.qos.logback</groupId>
      <artifactId>logback-core</artifactId>
      <version>1.5.22</version>
    </dependency>
    <dependency>
      <groupId>org.slf4j</groupId>
      <artifactId>slf4j-api</artifactId>
      <version>2.0.17</version>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <!-- HINWEIS: Die BOM steuert Dependency-Versionen (auch direkte) via
       dependencyManagement. Wir pinnen hier explizit 3.5.9 für Transparenz
       und um die Version im POM sichtbar zu machen. -->
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
    <version>3.5.9</version>
  </dependency>
  <!-- ... weitere Starter ... -->
</dependencies>

<build>
  <plugins>
    <plugin>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-maven-plugin</artifactId>
      <version>3.5.9</version>
    </plugin>
  </plugins>
</build>
```

### Nach AddSpringBootParent:

```xml
<parent>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-parent</artifactId>
  <version>3.5.9</version>
  <relativePath/> <!-- Wichtig für Monorepo/Workspace -->
</parent>

<dependencies>
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
    <!-- Version nicht nötig - vom Parent verwaltet -->
  </dependency>
</dependencies>
```
