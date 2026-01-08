# EJB WildFly Beispielprojekte

Recherche: 2025-01-19
Kriterien: WildFly/Jakarta EE-basiert, nicht älter als 5 Jahre, Enterprise-Level Komplexität

---

## Bewertungsübersicht

| Projekt | App Server | Version | Status |
|---------|------------|---------|--------|
| **CargoTracker** | Payara, GlassFish, Open Liberty | Jakarta EE 10 | ✅ Modern |
| **deftERP** | WildFly 9 | Java EE 7 | ❌ Veraltet (2015) |
| **Petstore EE7** | WildFly 10-26 | Java EE 7 | ⚠️ Grenzwertig |
| **Traffic System** | Payara/GlassFish | Jakarta EE | ✅ Modern (2024) |
| **WildFly Quickstarts** | WildFly 31+ | Jakarta EE 10 | ✅ Aktuell |

**Hinweis:** WildFly 9 ist von 2015 - komplett veraltet. Aktuell ist WildFly 31/32.

---

## Tier 1: Aktuelle Enterprise-Level Referenzprojekte

### 1. Eclipse CargoTracker (Offizielle Referenz)

| Eigenschaft | Wert |
|-------------|------|
| URL | https://github.com/eclipse-ee4j/cargotracker |
| Stars / Forks | 371 / 176 |
| Letztes Update | September 2023 (v3.0 - Jakarta EE 10) |
| Build | Maven |
| App Server | Payara, GlassFish, Open Liberty |

**Business Domain:** Cargo/Logistik-Tracking-System
- Public Tracking Interface
- Admin Dashboard für Shipping Operations
- Port Personnel Event Registration
- REST APIs
- Batch CSV Processing

**Technologie-Stack:**
- Jakarta EE: Faces, CDI, Enterprise Beans, JPA, REST, Batch, JSON Binding, Bean Validation, Messaging
- DDD-Implementation (Domain-Driven Design)
- Kubernetes Deployment

**Migrationswert:** ★★★★★ - Offizielle Referenz mit vollständiger DDD-Implementierung

**Einschränkung:** Kein WildFly-Support dokumentiert

---

### 2. Smart Urban Traffic Management System (SUTMS)

| Eigenschaft | Wert |
|-------------|------|
| URL | https://github.com/RashmikaJayasooriya/Smart-Urban-Traffic-Management-System |
| Letztes Update | Mai 2024 |
| Build | Maven (Multi-Module) |
| App Server | Payara/GlassFish |
| Datenbank | SQLite |

**Business Domain:** IoT-basierte Urban Traffic Analysis
- IoT Device Data Simulation
- Real-time Traffic Metrics Processing
- Asynchrones Processing via JMS

**Architektur:** 4-Layer Pattern
- Module: core, ejb, web, ear
- EJB: Stateless, Singleton, Message-Driven Beans

**Technologie-Stack:**
- Java EE mit EJB
- JMS (Java Message Service)
- Servlets und JSP

**Migrationswert:** ★★★★☆ - Moderne Multi-Module Architektur, JMS Patterns, MDB

---

## Tier 0: Bereits vorhanden / Primär

### WildFly Quickstarts (Geklont) ✅

| Eigenschaft | Wert |
|-------------|------|
| URL | https://github.com/wildfly/quickstart |
| Status | Bereits geklont |
| App Server | WildFly 31+ |
| Version | Jakarta EE 10 |

**Relevante Quickstarts für Migration:**

| Quickstart | EJB-Features | Priorität |
|------------|--------------|-----------|
| `ejb-remote` | Remote EJB via JNDI (Stateless + Stateful) | Hoch |
| `ejb-timer` | Timer Service mit @Schedule/@Timeout | Hoch |
| `helloworld-mdb` | Message-Driven Bean mit JMS | Hoch |
| `helloworld-singleton` | Singleton Bean Lifecycle | Hoch |
| `kitchensink` | Jakarta EE 10 komplett (JSF, CDI, EJB, JPA) | Hoch |
| `bmt` | Bean-Managed Transactions | Mittel |
| `cmt` | Container-Managed Transactions mit JMS | Mittel |
| `ejb-security-context-propagation` | Security Context Propagation | Niedrig |
| `ejb-txn-remote-call` | Remote transaktionale EJB-Aufrufe | Niedrig |

---

## Tier 2: Veraltete Projekte (nur als Referenz)

### deftERP ❌

| Eigenschaft | Wert |
|-------------|------|
| URL | https://github.com/medbounaga/deftERP |
| App Server | WildFly 9 (2015!) |
| Status | **Veraltet** |

### Java Petstore EE7 ⚠️

| Eigenschaft | Wert |
|-------------|------|
| URL | https://github.com/agoncal/agoncal-application-petstore-ee7 |
| App Server | WildFly 10-26 |
| Status | **Grenzwertig** - Java EE 7, nicht Jakarta EE |

---

## Technologie-Abdeckung für Migration

| Feature | CargoTracker | Traffic System | Quickstarts |
|---------|:-----------:|:--------------:|:-----------:|
| Stateless EJB | ✅ | ✅ | ✅ |
| Stateful EJB | ✅ | ⭕ | ✅ |
| Singleton EJB | ✅ | ✅ | ✅ |
| MDB | ✅ | ✅ | ✅ |
| Timer Service | ✅ | ⭕ | ✅ |
| JPA | ✅ | ✅ | ✅ |
| CDI | ✅ | ⭕ | ✅ |
| JAX-RS | ✅ | ⭕ | ✅ |
| JSF | ✅ | ✅ | ✅ |
| Batch | ✅ | ⭕ | ⭕ |
| JMS | ✅ | ✅ | ✅ |
| Security | ✅ | ⭕ | ✅ |
| Transactions | ✅ | ✅ | ✅ |

✅ = Vollständig | ⭕ = Teilweise/Nicht vorhanden

---

## Empfehlung

1. **WildFly Quickstarts** - Primäre Testbasis (bereits geklont, aktuell)
2. **Traffic System** - Für JMS/MDB Patterns klonen und analysieren
3. **CargoTracker** - Als Referenz für komplexe DDD-Szenarien
