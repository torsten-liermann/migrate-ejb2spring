# JSF‑Migration – JoinFaces‑Strategie (Projektentscheidung)

Projektentscheidung (2026‑01‑16): JSF bleibt als UI‑Technologie erhalten und wird über JoinFaces in Spring Boot betrieben. Diese Entscheidung ist an das aktuelle Projekt gebunden und muss bei anderen Zielarchitekturen neu bewertet werden.

## Optionen im Projektkontext

| Ansatz | Beschreibung | Status im Projekt |
|---|---|---|
| JoinFaces | JSF auf Spring Boot, UI bleibt erhalten | Projektentscheidung |
| Thymeleaf | Server‑Side Templates, UI‑Neuentwicklung | Nicht Teil dieses Projekts |
| SPA + REST | Separates Frontend mit Backend‑API | Nicht Teil dieses Projekts |

## Automatisierung im Projekt

Folgende Rezepte werden im Migrationslauf verwendet:

- `AddJoinFacesDependencies`: fügt JoinFaces‑BOM, Starter und Maven‑Plugin hinzu; erkennt PrimeFaces/MyFaces.
- `MoveJsfResources`: verschiebt JSF‑Ressourcen nach `META-INF`/`META-INF/resources`.
- `MarkJsfForMigration`: markiert JSF‑Klassen mit `@NeedsReview(category=CONFIGURATION)`.

## Beispiel: POM‑Änderungen durch AddJoinFacesDependencies

.Beispiel: JoinFaces‑BOM und Starter
```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.joinfaces</groupId>
            <artifactId>joinfaces-dependencies</artifactId>
            <version>5.3.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <!-- Mojarra (Default) oder MyFaces, abhängig von der Erkennung -->
    <dependency>
        <groupId>org.joinfaces</groupId>
        <artifactId>jsf-spring-boot-starter</artifactId>
    </dependency>

    <!-- Optional: PrimeFaces, wenn erkannt -->
    <dependency>
        <groupId>org.joinfaces</groupId>
        <artifactId>primefaces-spring-boot-starter</artifactId>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <groupId>org.joinfaces</groupId>
            <artifactId>joinfaces-maven-plugin</artifactId>
            <version>5.3.0</version>
            <executions>
                <execution>
                    <goals>
                        <goal>classpath-scan</goal>
                    </goals>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

Hinweis: Der tatsächliche Starter ist abhängig von der Erkennung (`jsf-spring-boot-starter` oder `myfaces-spring-boot-starter`). PrimeFaces wird ergänzt, wenn `org.primefaces:primefaces` im Projekt vorhanden ist.

## Beispiel: Ressourcen‑Verschiebung

.Beispiel: Zielpfade in Spring Boot

- `WEB-INF/faces-config.xml` → `src/main/resources/META-INF/faces-config.xml`
- `webapp/*.xhtml` → `src/main/resources/META-INF/resources/*.xhtml`
- `webapp/**/*.xhtml` → `src/main/resources/META-INF/resources/**/*.xhtml`

## Beispiel: Markierung mit @NeedsReview

.Beispiel: MarkJsfForMigration
```java
@NeedsReview(
    reason = "JSF backing bean: JoinFaces‑Struktur und Scopes prüfen",
    category = CONFIGURATION,
    originalCode = "Uses: @ViewScoped, FacesContext",
    suggestedAction = "Move: faces-config.xml -> META-INF, *.xhtml -> META-INF/resources"
)
@Named
@ViewScoped
public class BookingViewBean {
}
```

## Kompatibilitätsangaben (Analysestand aus Rezept‑Kommentar)

| JoinFaces | Spring Boot | Jakarta EE | Java |
|---|---|---|---|
| 5.3.x | 3.3.x–3.5.x | EE 10 | 17+ |

Getestet (Analysestand): JoinFaces 5.3.0, Spring Boot 3.5.x, Jakarta EE 10, Java 21.

## Scope‑Abbildung (JoinFaces)

| CDI‑Scope | Abbildung | Hinweis |
|---|---|---|
| `@RequestScoped` | Spring `request` | Scope‑Mapping |
| `@SessionScoped` | Spring `session` | Scope‑Mapping |
| `@ViewScoped` | JoinFaces‑ViewScope | JoinFaces‑eigener Scope |
| `@ConversationScoped` | `session` | Semantikverlust, Review erforderlich |

## Prüfpunkte für das Projekt

- `faces-config.xml`‑Version und kompatible JSF‑Version
- `javax.faces.PROJECT_STAGE` bzw. `joinfaces.jsf.project-stage`
- PrimeFaces‑Theme‑Konfiguration (falls verwendet)
- ResourceLibraryContracts (falls verwendet)
- CDI‑Erweiterungen und Custom Scopes

## Quellen

- https://github.com/joinfaces/joinfaces
- https://docs.joinfaces.org/
