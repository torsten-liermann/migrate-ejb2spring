# JSF migration – JoinFaces strategy (project decision)
Project decision (2026-01-16): JSF remains as a UI technology and is operated via JoinFaces in Spring Boot. This decision is tied to the current project and must be reevaluated for other target architectures.
## Options in project context
| approach | Description | Status in the project |
|---|---|---|
| JoinFaces | JSF on Spring Boot, UI remains | Project decision |
| Thymeleaf | Server-side templates, new UI development | Not part of this project |
| SPA + REST | Separate frontend with backend API | Not part of this project |
## Automation in the project
The following recipes are used in the migration run:
- `AddJoinFacesDependencies`: adds JoinFaces BOM, starter and Maven plugin; recognizes PrimeFaces/MyFaces.
- `MoveJsfResources`: moves JSF resources to `META-INF`/`META-INF/resources`.
- `MarkJsfForMigration`: mark JSF classes with `@NeedsReview(category=CONFIGURATION)`.
## Example: POM changes through AddJoinFacesDependencies
.Example: JoinFaces‑BOM and Starter
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
    <!-- Mojarra (default) or MyFaces, depending on the recognition -->
    <dependency>
        <groupId>org.joinfaces</groupId>
        <artifactId>jsf-spring-boot-starter</artifactId>
    </dependency>
<!-- Optional: PrimeFaces if detected -->
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
Note: The actual starter depends on the detection (`jsf-spring-boot-starter` or `myfaces-spring-boot-starter`). PrimeFaces is added if `org.primefaces:primefaces` is present in the project.
## Example: resource movement
.Example: Target paths in Spring Boot
- `WEB-INF/faces-config.xml` -> `src/main/resources/META-INF/faces-config.xml`
- `webapp/*.xhtml` -> `src/main/resources/META-INF/resources/*.xhtml`
- `webapp/**/*.xhtml` -> `src/main/resources/META-INF/resources/**/*.xhtml`
## Example: Mark with @NeedsReview
.Example: MarkJsfForMigration
```java
@NeedsReview(
reason = "JSF backing bean: Check JoinFaces structure and scopes",
    category = CONFIGURATION,
    originalCode = "Uses: @ViewScoped, FacesContext",
    suggestedAction = "Move: faces-config.xml -> META-INF, *.xhtml -> META-INF/resources"
)
@Named
@ViewScoped
public class BookingViewBean {
}
```

## Compatibility information (analysis status from recipe comment)
| JoinFaces | Spring Boot | Jakarta EE | Java |
|---|---|---|---|
| 5.3.x | 3.3.x–3.5.x | EE 10 | 17+ |
Tested (analysis level): JoinFaces 5.3.0, Spring Boot 3.5.x, Jakarta EE 10, Java 21.
## Scope mapping (JoinFaces)
| CDI‑Scope | Figure | Note |
|---|---|---|
| `@RequestScoped` | Spring `request` | Scope mapping |
| `@SessionScoped` | Spring `session` | Scope mapping |
| `@ViewScoped` | JoinFaces‑ViewScope | JoinFaces’ own scope |
| `@ConversationScoped` | `session` | Semantic loss, review required |
## Checkpoints for the project
- `faces-config.xml` version and compatible JSF version
- `javax.faces.PROJECT_STAGE` or `joinfaces.jsf.project-stage`
- PrimeFaces theme configuration (if used)
- ResourceLibraryContracts (if used)
- CDI extensions and custom scopes
## Sources
- https://github.com/joinfaces/joinfaces
- https://docs.joinfaces.org/