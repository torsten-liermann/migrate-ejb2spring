# OpenRewrite‑Maven‑Rezepte (Projektstand)

Stand: `migration-test/rewrite.yml`

Dieses Dokument fasst die Maven‑bezogenen Rezepte zusammen, die im Projekt verwendet werden. Die Angaben beziehen sich auf die aktuelle `rewrite.yml` und sollten bei Versionswechseln aktualisiert werden.

## Verwendete OpenRewrite‑Klassen

| Klasse | Zweck |
|---|---|
| `org.openrewrite.maven.AddDependency` | Dependency hinzufügen |
| `org.openrewrite.maven.AddManagedDependency` | BOM/Managed Dependency hinzufügen |
| `org.openrewrite.maven.AddPlugin` | Maven‑Plugin hinzufügen |
| `org.openrewrite.maven.AddParentPom` | Parent‑POM setzen |
| `org.openrewrite.maven.ChangeParentPom` | Parent‑POM ändern |

## Rezept: AddSpringDependencies

Beispiel (Auszug):
```yaml
name: com.github.rewrite.migration.AddSpringDependencies
recipeList:
  - org.openrewrite.maven.AddDependency:
      groupId: org.springframework
      artifactId: spring-context
      version: 6.1.14
  - org.openrewrite.maven.AddDependency:
      groupId: org.springframework
      artifactId: spring-web
      version: 6.1.14
  - org.openrewrite.maven.AddDependency:
      groupId: org.springframework
      artifactId: spring-tx
      version: 6.1.14
  - org.openrewrite.maven.AddDependency:
      groupId: org.springframework
      artifactId: spring-jms
      version: 6.1.14
```

## Rezept: AddSpringBootParent (Option 1)

Beispiel (Auszug):
```yaml
name: com.github.rewrite.migration.AddSpringBootParent
recipeList:
  - org.openrewrite.maven.AddParentPom:
      groupId: org.springframework.boot
      artifactId: spring-boot-starter-parent
      version: 3.5.9
      relativePath: ""
  - org.openrewrite.maven.AddDependency:
      groupId: org.springframework.boot
      artifactId: spring-boot-starter-web
  - org.openrewrite.maven.AddDependency:
      groupId: org.springframework.boot
      artifactId: spring-boot-starter-data-jpa
  - org.openrewrite.maven.AddPlugin:
      groupId: org.springframework.boot
      artifactId: spring-boot-maven-plugin
```

## Rezept: AddSpringBootBom (Option 2)

Beispiel (Auszug):
```yaml
name: com.github.rewrite.migration.AddSpringBootBom
recipeList:
  - org.openrewrite.maven.AddManagedDependency:
      groupId: org.springframework.boot
      artifactId: spring-boot-dependencies
      version: 3.5.9
      scope: import
      type: pom
  - org.openrewrite.maven.AddManagedDependency:
      groupId: ch.qos.logback
      artifactId: logback-classic
      version: 1.5.22
  - org.openrewrite.maven.AddManagedDependency:
      groupId: ch.qos.logback
      artifactId: logback-core
      version: 1.5.22
  - org.openrewrite.maven.AddManagedDependency:
      groupId: org.slf4j
      artifactId: slf4j-api
      version: 2.0.17
```

## Alias: AddSpringBootStarter

`com.github.rewrite.migration.AddSpringBootStarter` ruft die BOM‑Variante auf und ergänzt die gewünschten Starter. Details stehen in `migration-test/rewrite.yml`.
