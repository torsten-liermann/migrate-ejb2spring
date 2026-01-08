# EJB-Annotationen als Noop-Marker abbilden

Dieses Dokument beschreibt eine migrationsbegleitende Abbildung von EJB-Annotationen auf eigene Marker-Annotationen ohne Laufzeitwirkung. Ziel ist, die semantischen Hinweise im Quelltext sichtbar zu halten, ohne die Jakarta‑EJB‑API als Build‑Abhängigkeit weiterzuführen.

## Überblick

EJB-Annotationen wie `@LocalBean`, `@Stateless` oder `@TransactionAttribute` sind deklarative Hinweise auf Container-Semantik. In einer Spring‑basierten Zielarchitektur sollen diese Hinweise im Code erkennbar bleiben, jedoch ohne die EJB‑API zur Kompilierung zu benötigen. Dazu werden sie auf projektspezifische Marker-Annotationen umgeschrieben, die keine Laufzeitwirkung haben und ausschließlich als Migrationshinweis dienen.

## Marker-Annotationen

Die Marker-Annotationen werden im Projekt unter `com.github.migration.annotations` bereitgestellt. Sie sind syntaktisch kompatibel zu den ursprünglichen EJB-Annotationen, tragen aber keine Laufzeit-Semantik.

Der Marker-Satz umfasst alle EJB-Annotationen. Beispiele:

- `com.github.migration.annotations.EjbStateless`
- `com.github.migration.annotations.EjbStateful`
- `com.github.migration.annotations.EjbSingleton`
- `com.github.migration.annotations.EjbTransactionAttribute` (optional mit `String value()` für den Originalwert)

.Beispiel: Marker-Definitionen
[source,java]
----
package com.github.migration.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface EjbStateless {
}

@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.SOURCE)
public @interface EjbTransactionAttribute {
    String value();
}
----

## OpenRewrite-Recipe

Die Migration ersetzt die EJB-Annotationen durch Marker-Annotationen und bereinigt die Imports.

- `@Stateless` → `@EjbStateless`
- `@Stateful` → `@EjbStateful`
- `@Singleton` → `@EjbSingleton`
- `@LocalBean` → `@EjbLocalBean`
- `@TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)` → `@EjbTransactionAttribute("REQUIRES_NEW")`
- Import-Cleanup der EJB-Annotationen

.Beispiel: Quelltext vor/nach der Abbildung
[source,java]
----
// Vorher (EJB)
import jakarta.ejb.Stateless;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;

@Stateless
@TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
public class BookingService {
}

// Nachher (Marker)
import com.github.migration.annotations.EjbStateless;
import com.github.migration.annotations.EjbTransactionAttribute;

@EjbStateless
@EjbTransactionAttribute("REQUIRES_NEW")
public class BookingService {
}
----

## Einordnung

Die Marker-Annotationen ersetzen keine funktionale Migration. Sie dienen ausschließlich als Migrationshinweis und tragen keine Laufzeit‑Semantik. Die fachliche Umsetzung der EJB‑Semantik erfolgt über Spring‑Mechanismen und weitere Recipes.
