# Weld–Spring-Boot-Integration – Analyse

Datum: 2026-01-19
Status: Analyse abgeschlossen

Diese Analyse beschreibt die beobachtete Integration von Weld (CDI) in einer Spring‑Boot‑Umgebung mit JoinFaces. Sie dokumentiert Container‑Beziehungen, Scope‑Mapping und die sichtbaren Bridges sowie die Auswirkungen auf Migrationen. Aussagen, die auf Quelltexten beruhen, beziehen sich auf den Analysestand vom 2026‑01‑19 und müssen bei Versionswechseln erneut validiert werden.

## Analyseumfang

Untersucht wurden JoinFaces‑Autokonfigurationen und der Weld‑Bootstrap in einer Spring‑Boot‑Anwendung. Die folgenden Quelltext‑Pfadsegmente wurden ausgewertet:

- `joinfaces‑autoconfigure/.../weld/WeldSpringBootAutoConfiguration.java`
- `joinfaces/.../weld/WeldServletContainerInitializerRegistrationBean.java`
- `joinfaces/.../weld/NoopContainer.java`
- `joinfaces‑autoconfigure/.../scopemapping/CdiScopeAnnotationsAutoConfiguration.java`
- `joinfaces‑autoconfigure/.../scopemapping/CustomScopeAnnotationConfigurer.java`
- `joinfaces‑autoconfigure/.../javaxfaces/JsfBeansAutoConfiguration.java`
- `joinfaces/.../viewscope/ViewScope.java`

## Container‑Beziehung

Die beobachtete Integration entspricht einer Koexistenz zweier Container. Spring verwaltet seine Beans unabhängig von Weld, und Weld verwaltet CDI‑Beans unabhängig von Spring. Eine generische Bridge für eine gemeinsame Bean‑Suche oder wechselseitige Injection ist in den untersuchten Quelltexten nicht erkennbar.

Indizien im JoinFaces‑Quelltext (Analysestand):

- `WeldSpringBootAutoConfiguration` registriert Weld als Servlet‑Initializer, ohne eine Anbindung an die Spring‑BeanFactory.
- `NoopContainer` implementiert ein leeres Container‑Interface, ohne Container‑Verwaltung in Spring zu integrieren.

## Lifecycle‑Integration

Spring und Weld führen ihre Lifecycle‑Callbacks jeweils im eigenen Container aus. `@PostConstruct` und `@PreDestroy` werden getrennt verwaltet. Es gibt keine koordinierte Reihenfolge über beide Container hinweg; Start und Shutdown erfolgen unabhängig.

## Scope‑Mapping

JoinFaces mappt CDI‑Scopes auf Spring‑Scopes, ohne CDI‑Semantik in Spring zu implementieren. Beobachtetes Mapping (Analysestand):

- `@RequestScoped` → `request`
- `@SessionScoped` → `session`
- `@ApplicationScoped` → `application`
- `@ConversationScoped` → `session`
- `@ViewScoped` → JoinFaces‑eigener View‑Scope

Das Mapping von `@ConversationScoped` auf `session` reduziert die Semantik des Conversation‑Scopes. Diese Abweichung ist bei einer Migration explizit zu berücksichtigen.

## Bean‑Discovery und Injection

Es wurde kein Mechanismus gefunden, der Beans aus dem jeweils anderen Container sichtbar macht. Das betrifft sowohl Injection als auch Event‑Dispatching:

- CDI‑Beans sind in Spring nicht als reguläre Beans sichtbar.
- Spring‑Beans sind im CDI‑Container nicht als CDI‑Beans verfügbar.
- CDI‑Events bleiben im CDI‑Container; Spring‑Events bleiben im Spring‑Container.

Eine Ausnahme betrifft JSF‑Artefakte, die JoinFaces als Spring‑Beans bereitstellt (Einweg‑Zugriff für Spring‑Beans).

## Erkennbare Bridges (Analysestand)

- `CustomScopeAnnotationConfigurer`: mappt CDI‑Scope‑Annotationen auf Spring‑Scopes für Spring‑Beans, ohne Weld‑Integration.
- `JsfBeansAutoConfiguration`: exponiert JSF‑Artefakte als Spring‑Beans.
- `JsfBeansAnnotationPostProcessor`: injiziert JSF‑bezogene Artefakte in Spring‑Beans.

Diese Mechanismen ersetzen keine bidirektionale CDI‑Spring‑Integration, sondern sind punktuelle Anpassungen im Spring‑Container.

## Auswirkungen auf Migrationen

Bei einer Koexistenz von Weld und Spring sind folgende Effekte zu erwarten:

- Keine Cross‑Container‑Injection zwischen CDI‑Beans und Spring‑Beans.
- CDI‑Interceptors und CDI‑Decorators greifen nicht auf Spring‑Beans.
- CDI‑Events sind nicht mit Spring‑Events gekoppelt.
- `@ConversationScoped` verliert Semantik durch Mapping auf `session`.

## Migrationsoptionen

- Vollständige Migration nach Spring, um einen einheitlichen Container zu erreichen.
- JoinFaces mit Weld für den JSF‑Layer, akzeptiert aber die Container‑Trennung.
- Eine bidirektionale CDI‑Spring‑Bridge ist in den untersuchten Projekten nicht nachweisbar (Analysestand); für eine solche Integration wäre eine Eigenentwicklung erforderlich.

## Quellen

- https://github.com/joinfaces/joinfaces
- https://github.com/spring-projects/spring-framework/issues/22243
- https://deltaspike.apache.org/addons.html
