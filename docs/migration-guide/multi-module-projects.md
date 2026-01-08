# Multi-Modul-Projekte – Architekturhinweise

Bei der Migration von Jakarta-EE-Anwendungen zu Spring Boot bleibt eine bestehende Multi-Modul-Struktur erhalten. Spring Boot Executable JARs setzen kein Single-Modul-Projekt voraus.

## Modulstruktur nach der Migration

Das EAR-Modul (oder bei Projekten ohne EAR das WAR-Modul) wird zum Application-Modul. Dieses Modul enthält die `@SpringBootApplication`-Klasse und bildet den Einstiegspunkt der Anwendung. Die übrigen Module – etwa für Persistenz, Domain-Modell, API oder Geschäftslogik – bleiben als eigenständige Maven-Module bestehen und werden als Dependencies eingebunden.

```
projekt/
├── pom.xml                    (Parent POM)
├── app/                       (ehemals EAR/WAR)
│   ├── pom.xml               (spring-boot-maven-plugin hier)
│   └── src/main/java/
│       └── com/example/Application.java
├── domain/
│   ├── pom.xml
│   └── src/main/java/
├── persistence/
│   ├── pom.xml
│   └── src/main/java/
└── api/
    ├── pom.xml
    └── src/main/java/
```

Das `spring-boot-maven-plugin` wird ausschließlich im Application-Modul konfiguriert. Die anderen Module verwenden Standard-JAR-Packaging ohne Spring-Boot-spezifische Plugins.

## Konfiguration des Application-Moduls

Das Application-Modul deklariert die fachlichen Module als Dependencies:

```xml
<dependencies>
    <dependency>
        <groupId>${project.groupId}</groupId>
        <artifactId>domain</artifactId>
        <version>${project.version}</version>
    </dependency>
    <dependency>
        <groupId>${project.groupId}</groupId>
        <artifactId>persistence</artifactId>
        <version>${project.version}</version>
    </dependency>
    <dependency>
        <groupId>${project.groupId}</groupId>
        <artifactId>api</artifactId>
        <version>${project.version}</version>
    </dependency>
</dependencies>
```

Das Executable JAR enthält nach dem Build alle Module als eingebettete JARs.

## Spring Modulith

Für Projekte, die eine explizite Modularisierung mit definierten Modulabhängigkeiten und -grenzen anstreben, bietet Spring Modulith eine Ergänzung. Spring Modulith ermöglicht:

- Definition und Validierung von Modulabhängigkeiten zur Buildzeit
- Dokumentationsgenerierung der Modulstruktur
- Event-basierte Kommunikation zwischen Modulen
- Integrationstests auf Modulebene

Die Verwendung von Spring Modulith ist optional und projektspezifisch zu entscheiden.

## Quellen

- https://docs.spring.io/spring-modulith/reference/
- https://docs.spring.io/spring-boot/docs/current/maven-plugin/reference/html/
