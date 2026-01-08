package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.properties.Assertions.properties;
import static org.openrewrite.xml.Assertions.xml;

class MigratePersistenceXmlToPropertiesTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigratePersistenceXmlToProperties());
    }

    @DocumentExample
    @Test
    void migrateBasicPersistenceXml() {
        rewriteRun(
            // Input: persistence.xml with basic configuration
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <persistence version="3.0" xmlns="https://jakarta.ee/xml/ns/persistence">
                    <persistence-unit name="TestUnit" transaction-type="JTA">
                        <jta-data-source>java:app/jdbc/TestDatabase</jta-data-source>
                        <properties>
                            <property name="jakarta.persistence.schema-generation.database.action" value="create"/>
                            <property name="hibernate.dialect" value="org.hibernate.dialect.PostgreSQLDialect"/>
                            <property name="hibernate.show_sql" value="true"/>
                        </properties>
                    </persistence-unit>
                </persistence>
                """,
                spec -> spec.path("src/main/resources/META-INF/persistence.xml")
            ),
            // Expect: application.properties generated with Spring Boot equivalents
            properties(
                null,
                """
                # Spring Boot Application Properties
                # Generated from persistence.xml migration

                # Migrated from persistence.xml (persistence-unit: TestUnit)
                # BEGIN DATASOURCE TODO (jndi=java:app/jdbc/TestDatabase)
                # TODO: DATASOURCE CONFIGURATION REQUIRED
                # Original JNDI: java:app/jdbc/TestDatabase
                # Configure one of the following:
                # spring.datasource.url=jdbc:postgresql://localhost:5432/yourdb
                # spring.datasource.username=
                # spring.datasource.password=
                # spring.datasource.driver-class-name=org.postgresql.Driver
                # END DATASOURCE TODO
                # NOTE: spring.jpa.database-platform is usually not needed with Hibernate 6.x (auto-detected)

                spring.jpa.hibernate.ddl-auto=create
                spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect
                spring.jpa.show-sql=true
                """,
                spec -> spec.path("src/main/resources/application.properties")
            )
        );
    }

    @Test
    void migrateWithExistingApplicationProperties() {
        rewriteRun(
            // Input: persistence.xml
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <persistence version="3.0" xmlns="https://jakarta.ee/xml/ns/persistence">
                    <persistence-unit name="AppUnit">
                        <properties>
                            <property name="hibernate.show_sql" value="true"/>
                            <property name="hibernate.format_sql" value="true"/>
                        </properties>
                    </persistence-unit>
                </persistence>
                """,
                spec -> spec.path("src/main/resources/META-INF/persistence.xml")
            ),
            // Input: existing application.properties
            properties(
                """
                server.port=8080
                spring.application.name=myapp
                """,
                """
                server.port=8080
                spring.application.name=myapp

                # ===== JPA Configuration (migrated from persistence.xml) =====
                # Migrated from persistence.xml (persistence-unit: AppUnit)

                spring.jpa.show-sql=true
                spring.jpa.properties.hibernate.format_sql=true
                """,
                spec -> spec.path("src/main/resources/application.properties")
            )
        );
    }

    @Test
    void migrateDropAndCreateSchemaAction() {
        rewriteRun(
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <persistence version="3.0" xmlns="https://jakarta.ee/xml/ns/persistence">
                    <persistence-unit name="DevUnit">
                        <properties>
                            <property name="jakarta.persistence.schema-generation.database.action" value="drop-and-create"/>
                        </properties>
                    </persistence-unit>
                </persistence>
                """,
                spec -> spec.path("src/main/resources/META-INF/persistence.xml")
            ),
            properties(
                null,
                """
                # Spring Boot Application Properties
                # Generated from persistence.xml migration

                # Migrated from persistence.xml (persistence-unit: DevUnit)

                spring.jpa.hibernate.ddl-auto=create-drop
                """,
                spec -> spec.path("src/main/resources/application.properties")
            )
        );
    }

    @Test
    void migrateWithEclipseLinkProperties() {
        rewriteRun(
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <persistence version="3.0" xmlns="https://jakarta.ee/xml/ns/persistence">
                    <persistence-unit name="EclipseLinkUnit">
                        <properties>
                            <property name="eclipselink.logging.level.sql" value="FINE"/>
                            <property name="eclipselink.logging.level" value="FINE"/>
                            <property name="hibernate.show_sql" value="true"/>
                        </properties>
                    </persistence-unit>
                </persistence>
                """,
                spec -> spec.path("src/main/resources/META-INF/persistence.xml")
            ),
            properties(
                null,
                """
                # Spring Boot Application Properties
                # Generated from persistence.xml migration

                # Migrated from persistence.xml (persistence-unit: EclipseLinkUnit)
                # EclipseLink property (Spring Boot uses Hibernate): eclipselink.logging.level.sql=FINE
                # EclipseLink property (Spring Boot uses Hibernate): eclipselink.logging.level=FINE

                spring.jpa.show-sql=true
                """,
                spec -> spec.path("src/main/resources/application.properties")
            )
        );
    }

    @Test
    void migrateWithSqlLoadScript() {
        rewriteRun(
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <persistence version="3.0" xmlns="https://jakarta.ee/xml/ns/persistence">
                    <persistence-unit name="DataUnit">
                        <properties>
                            <property name="jakarta.persistence.sql-load-script-source" value="META-INF/import.sql"/>
                        </properties>
                    </persistence-unit>
                </persistence>
                """,
                spec -> spec.path("src/main/resources/META-INF/persistence.xml")
            ),
            properties(
                null,
                """
                # Spring Boot Application Properties
                # Generated from persistence.xml migration

                # Migrated from persistence.xml (persistence-unit: DataUnit)
                # TODO: Move META-INF/import.sql to src/main/resources/data.sql
                # NOTE: If using ddl-auto=create with data.sql, you may need:
                # spring.jpa.defer-datasource-initialization=true

                spring.sql.init.mode=always
                """,
                spec -> spec.path("src/main/resources/application.properties")
            )
        );
    }

    @Test
    void migrateCargoTrackerPersistenceXml() {
        // Test with actual Cargo Tracker persistence.xml structure
        rewriteRun(
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <persistence version="3.0" xmlns="https://jakarta.ee/xml/ns/persistence">
                    <persistence-unit name="CargoTrackerUnit" transaction-type="JTA">
                        <jta-data-source>java:app/jdbc/CargoTrackerDatabase</jta-data-source>
                        <properties>
                            <property name="jakarta.persistence.schema-generation.database.action" value="create"/>
                            <property name="hibernate.dialect" value="org.hibernate.dialect.PostgreSQLDialect"/>
                            <property name="hibernate.show_sql" value="true"/>
                            <property name="hibernate.format_sql" value="true"/>
                        </properties>
                    </persistence-unit>
                </persistence>
                """,
                spec -> spec.path("src/main/resources/META-INF/persistence.xml")
            ),
            properties(
                null,
                """
                # Spring Boot Application Properties
                # Generated from persistence.xml migration

                # Migrated from persistence.xml (persistence-unit: CargoTrackerUnit)
                # BEGIN DATASOURCE TODO (jndi=java:app/jdbc/CargoTrackerDatabase)
                # TODO: DATASOURCE CONFIGURATION REQUIRED
                # Original JNDI: java:app/jdbc/CargoTrackerDatabase
                # Configure one of the following:
                # spring.datasource.url=jdbc:postgresql://localhost:5432/yourdb
                # spring.datasource.username=
                # spring.datasource.password=
                # spring.datasource.driver-class-name=org.postgresql.Driver
                # END DATASOURCE TODO
                # NOTE: spring.jpa.database-platform is usually not needed with Hibernate 6.x (auto-detected)

                spring.jpa.hibernate.ddl-auto=create
                spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect
                spring.jpa.show-sql=true
                spring.jpa.properties.hibernate.format_sql=true
                """,
                spec -> spec.path("src/main/resources/application.properties")
            )
        );
    }

    @Test
    void noPersistenceXmlNoChanges() {
        // Test that nothing happens when there's no persistence.xml
        rewriteRun(
            properties(
                """
                server.port=8080
                """,
                spec -> spec.path("src/main/resources/application.properties")
            )
        );
    }

    @Test
    void migrateJakartaJdbcProperties() {
        // Test jakarta.persistence.jdbc.* to spring.datasource.* mapping (Jakarta EE 9+)
        rewriteRun(
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <persistence version="3.0" xmlns="https://jakarta.ee/xml/ns/persistence">
                    <persistence-unit name="JdbcUnit">
                        <properties>
                            <property name="jakarta.persistence.jdbc.url" value="jdbc:postgresql://localhost:5432/mydb"/>
                            <property name="jakarta.persistence.jdbc.user" value="dbuser"/>
                            <property name="jakarta.persistence.jdbc.password" value="dbpass"/>
                            <property name="jakarta.persistence.jdbc.driver" value="org.postgresql.Driver"/>
                        </properties>
                    </persistence-unit>
                </persistence>
                """,
                spec -> spec.path("src/main/resources/META-INF/persistence.xml")
            ),
            properties(
                null,
                """
                # Spring Boot Application Properties
                # Generated from persistence.xml migration

                # Migrated from persistence.xml (persistence-unit: JdbcUnit)

                spring.datasource.url=jdbc:postgresql://localhost:5432/mydb
                spring.datasource.username=dbuser
                spring.datasource.password=dbpass
                spring.datasource.driver-class-name=org.postgresql.Driver
                """,
                spec -> spec.path("src/main/resources/application.properties")
            )
        );
    }

    @Test
    void migrateJavaxJdbcProperties() {
        // Test javax.persistence.jdbc.* to spring.datasource.* mapping (Java EE 8)
        rewriteRun(
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <persistence version="2.2" xmlns="http://xmlns.jcp.org/xml/ns/persistence">
                    <persistence-unit name="LegacyJdbcUnit">
                        <properties>
                            <property name="javax.persistence.jdbc.url" value="jdbc:mysql://localhost:3306/legacy"/>
                            <property name="javax.persistence.jdbc.user" value="root"/>
                            <property name="javax.persistence.jdbc.password" value="secret"/>
                            <property name="javax.persistence.jdbc.driver" value="com.mysql.cj.jdbc.Driver"/>
                        </properties>
                    </persistence-unit>
                </persistence>
                """,
                spec -> spec.path("src/main/resources/META-INF/persistence.xml")
            ),
            properties(
                null,
                """
                # Spring Boot Application Properties
                # Generated from persistence.xml migration

                # Migrated from persistence.xml (persistence-unit: LegacyJdbcUnit)

                spring.datasource.url=jdbc:mysql://localhost:3306/legacy
                spring.datasource.username=root
                spring.datasource.password=secret
                spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver
                """,
                spec -> spec.path("src/main/resources/application.properties")
            )
        );
    }

    @Test
    void migrateDropSchemaActionToTodo() {
        // Test that "drop" schema action produces TODO comment (not auto-mapped)
        rewriteRun(
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <persistence version="3.0" xmlns="https://jakarta.ee/xml/ns/persistence">
                    <persistence-unit name="DropUnit">
                        <properties>
                            <property name="jakarta.persistence.schema-generation.database.action" value="drop"/>
                        </properties>
                    </persistence-unit>
                </persistence>
                """,
                spec -> spec.path("src/main/resources/META-INF/persistence.xml")
            ),
            properties(
                null,
                """
                # Spring Boot Application Properties
                # Generated from persistence.xml migration

                # Migrated from persistence.xml (persistence-unit: DropUnit)
                # TODO: schema-generation.database.action=drop has no Spring equivalent
                # Consider using spring.jpa.hibernate.ddl-auto=none and manual DDL scripts
                """,
                spec -> spec.path("src/main/resources/application.properties")
            )
        );
    }

    @Test
    void migrateNonJtaDataSource() {
        // Test non-jta-data-source extraction
        rewriteRun(
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <persistence version="3.0" xmlns="https://jakarta.ee/xml/ns/persistence">
                    <persistence-unit name="NonJtaUnit" transaction-type="RESOURCE_LOCAL">
                        <non-jta-data-source>java:comp/env/jdbc/MyDatabase</non-jta-data-source>
                        <properties>
                            <property name="hibernate.show_sql" value="true"/>
                        </properties>
                    </persistence-unit>
                </persistence>
                """,
                spec -> spec.path("src/main/resources/META-INF/persistence.xml")
            ),
            properties(
                null,
                """
                # Spring Boot Application Properties
                # Generated from persistence.xml migration

                # Migrated from persistence.xml (persistence-unit: NonJtaUnit)
                # BEGIN DATASOURCE TODO (jndi=java:comp/env/jdbc/MyDatabase)
                # TODO: DATASOURCE CONFIGURATION REQUIRED
                # Original JNDI: java:comp/env/jdbc/MyDatabase
                # Configure one of the following:
                # spring.datasource.url=jdbc:postgresql://localhost:5432/yourdb
                # spring.datasource.username=
                # spring.datasource.password=
                # spring.datasource.driver-class-name=org.postgresql.Driver
                # END DATASOURCE TODO

                spring.jpa.show-sql=true
                """,
                spec -> spec.path("src/main/resources/application.properties")
            )
        );
    }

    @Test
    void migrateHibernateUseSqlComments() {
        // Test hibernate.use_sql_comments mapping
        rewriteRun(
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <persistence version="3.0" xmlns="https://jakarta.ee/xml/ns/persistence">
                    <persistence-unit name="CommentsUnit">
                        <properties>
                            <property name="hibernate.show_sql" value="true"/>
                            <property name="hibernate.format_sql" value="true"/>
                            <property name="hibernate.use_sql_comments" value="true"/>
                        </properties>
                    </persistence-unit>
                </persistence>
                """,
                spec -> spec.path("src/main/resources/META-INF/persistence.xml")
            ),
            properties(
                null,
                """
                # Spring Boot Application Properties
                # Generated from persistence.xml migration

                # Migrated from persistence.xml (persistence-unit: CommentsUnit)

                spring.jpa.show-sql=true
                spring.jpa.properties.hibernate.format_sql=true
                spring.jpa.properties.hibernate.use_sql_comments=true
                """,
                spec -> spec.path("src/main/resources/application.properties")
            )
        );
    }

    @Test
    void migrateValidateSchemaAction() {
        // Test validate schema action mapping
        rewriteRun(
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <persistence version="3.0" xmlns="https://jakarta.ee/xml/ns/persistence">
                    <persistence-unit name="ValidateUnit">
                        <properties>
                            <property name="jakarta.persistence.schema-generation.database.action" value="validate"/>
                        </properties>
                    </persistence-unit>
                </persistence>
                """,
                spec -> spec.path("src/main/resources/META-INF/persistence.xml")
            ),
            properties(
                null,
                """
                # Spring Boot Application Properties
                # Generated from persistence.xml migration

                # Migrated from persistence.xml (persistence-unit: ValidateUnit)

                spring.jpa.hibernate.ddl-auto=validate
                """,
                spec -> spec.path("src/main/resources/application.properties")
            )
        );
    }

    @Test
    void migrateUpdateSchemaAction() {
        // Test update schema action mapping
        rewriteRun(
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <persistence version="3.0" xmlns="https://jakarta.ee/xml/ns/persistence">
                    <persistence-unit name="UpdateUnit">
                        <properties>
                            <property name="jakarta.persistence.schema-generation.database.action" value="update"/>
                        </properties>
                    </persistence-unit>
                </persistence>
                """,
                spec -> spec.path("src/main/resources/META-INF/persistence.xml")
            ),
            properties(
                null,
                """
                # Spring Boot Application Properties
                # Generated from persistence.xml migration

                # Migrated from persistence.xml (persistence-unit: UpdateUnit)

                spring.jpa.hibernate.ddl-auto=update
                """,
                spec -> spec.path("src/main/resources/application.properties")
            )
        );
    }

    @Test
    void migrateNoneSchemaAction() {
        // Test none schema action mapping
        rewriteRun(
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <persistence version="3.0" xmlns="https://jakarta.ee/xml/ns/persistence">
                    <persistence-unit name="NoneUnit">
                        <properties>
                            <property name="jakarta.persistence.schema-generation.database.action" value="none"/>
                        </properties>
                    </persistence-unit>
                </persistence>
                """,
                spec -> spec.path("src/main/resources/META-INF/persistence.xml")
            ),
            properties(
                null,
                """
                # Spring Boot Application Properties
                # Generated from persistence.xml migration

                # Migrated from persistence.xml (persistence-unit: NoneUnit)

                spring.jpa.hibernate.ddl-auto=none
                """,
                spec -> spec.path("src/main/resources/application.properties")
            )
        );
    }

    @Test
    void migrateOtherHibernateProperties() {
        // Test pass-through of other hibernate.* properties
        rewriteRun(
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <persistence version="3.0" xmlns="https://jakarta.ee/xml/ns/persistence">
                    <persistence-unit name="HibernateUnit">
                        <properties>
                            <property name="hibernate.jdbc.batch_size" value="50"/>
                            <property name="hibernate.order_inserts" value="true"/>
                            <property name="hibernate.order_updates" value="true"/>
                        </properties>
                    </persistence-unit>
                </persistence>
                """,
                spec -> spec.path("src/main/resources/META-INF/persistence.xml")
            ),
            properties(
                null,
                """
                # Spring Boot Application Properties
                # Generated from persistence.xml migration

                # Migrated from persistence.xml (persistence-unit: HibernateUnit)

                spring.jpa.properties.hibernate.jdbc.batch_size=50
                spring.jpa.properties.hibernate.order_inserts=true
                spring.jpa.properties.hibernate.order_updates=true
                """,
                spec -> spec.path("src/main/resources/application.properties")
            )
        );
    }

    @Test
    void jakartaPersistencePropertiesPassthrough() {
        // Test: jakarta.persistence.* properties are passed through to spring.jpa.properties.*
        // (no TODO anymore - they are valid JPA properties that Hibernate can process)
        rewriteRun(
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <persistence version="3.0" xmlns="https://jakarta.ee/xml/ns/persistence">
                    <persistence-unit name="PassthroughUnit">
                        <properties>
                            <property name="jakarta.persistence.lock.timeout" value="5000"/>
                            <property name="jakarta.persistence.query.timeout" value="10000"/>
                            <property name="jakarta.persistence.validation.mode" value="AUTO"/>
                        </properties>
                    </persistence-unit>
                </persistence>
                """,
                spec -> spec.path("src/main/resources/META-INF/persistence.xml")
            ),
            properties(
                null,
                """
                # Spring Boot Application Properties
                # Generated from persistence.xml migration

                # Migrated from persistence.xml (persistence-unit: PassthroughUnit)

                spring.jpa.properties.jakarta.persistence.lock.timeout=5000
                spring.jpa.properties.jakarta.persistence.query.timeout=10000
                spring.jpa.properties.jakarta.persistence.validation.mode=AUTO
                """,
                spec -> spec.path("src/main/resources/application.properties")
            )
        );
    }

    @Test
    void lockTimeoutIsPassedThrough() {
        // Test for jakarta.persistence.lock.timeout passthrough
        rewriteRun(
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <persistence version="3.0" xmlns="https://jakarta.ee/xml/ns/persistence">
                    <persistence-unit name="LockTimeoutUnit">
                        <properties>
                            <property name="jakarta.persistence.lock.timeout" value="5000"/>
                        </properties>
                    </persistence-unit>
                </persistence>
                """,
                spec -> spec.path("src/main/resources/META-INF/persistence.xml")
            ),
            properties(
                null,
                """
                # Spring Boot Application Properties
                # Generated from persistence.xml migration

                # Migrated from persistence.xml (persistence-unit: LockTimeoutUnit)

                spring.jpa.properties.jakarta.persistence.lock.timeout=5000
                """,
                spec -> spec.path("src/main/resources/application.properties")
            )
        );
    }

    @Test
    void queryTimeoutIsPassedThrough() {
        // Test for jakarta.persistence.query.timeout passthrough
        rewriteRun(
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <persistence version="3.0" xmlns="https://jakarta.ee/xml/ns/persistence">
                    <persistence-unit name="QueryTimeoutUnit">
                        <properties>
                            <property name="jakarta.persistence.query.timeout" value="30000"/>
                        </properties>
                    </persistence-unit>
                </persistence>
                """,
                spec -> spec.path("src/main/resources/META-INF/persistence.xml")
            ),
            properties(
                null,
                """
                # Spring Boot Application Properties
                # Generated from persistence.xml migration

                # Migrated from persistence.xml (persistence-unit: QueryTimeoutUnit)

                spring.jpa.properties.jakarta.persistence.query.timeout=30000
                """,
                spec -> spec.path("src/main/resources/application.properties")
            )
        );
    }

    @Test
    void validationModeIsPassedThrough() {
        // Test for jakarta.persistence.validation.mode passthrough
        rewriteRun(
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <persistence version="3.0" xmlns="https://jakarta.ee/xml/ns/persistence">
                    <persistence-unit name="ValidationModeUnit">
                        <properties>
                            <property name="jakarta.persistence.validation.mode" value="CALLBACK"/>
                        </properties>
                    </persistence-unit>
                </persistence>
                """,
                spec -> spec.path("src/main/resources/META-INF/persistence.xml")
            ),
            properties(
                null,
                """
                # Spring Boot Application Properties
                # Generated from persistence.xml migration

                # Migrated from persistence.xml (persistence-unit: ValidationModeUnit)

                spring.jpa.properties.jakarta.persistence.validation.mode=CALLBACK
                """,
                spec -> spec.path("src/main/resources/application.properties")
            )
        );
    }

    @Test
    void javaxPersistencePropertiesPassthrough() {
        // Test: javax.persistence.* properties are also passed through (Java EE 8 compatibility)
        rewriteRun(
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <persistence version="2.2" xmlns="http://xmlns.jcp.org/xml/ns/persistence">
                    <persistence-unit name="JavaxPassthroughUnit">
                        <properties>
                            <property name="javax.persistence.lock.timeout" value="3000"/>
                            <property name="javax.persistence.query.timeout" value="15000"/>
                        </properties>
                    </persistence-unit>
                </persistence>
                """,
                spec -> spec.path("src/main/resources/META-INF/persistence.xml")
            ),
            properties(
                null,
                """
                # Spring Boot Application Properties
                # Generated from persistence.xml migration

                # Migrated from persistence.xml (persistence-unit: JavaxPassthroughUnit)

                spring.jpa.properties.javax.persistence.lock.timeout=3000
                spring.jpa.properties.javax.persistence.query.timeout=15000
                """,
                spec -> spec.path("src/main/resources/application.properties")
            )
        );
    }

    @Test
    void migrateHibernateEjbInterceptor() {
        // Test: hibernate.ejb.interceptor (deprecated) → spring.jpa.properties.hibernate.session_factory.interceptor
        rewriteRun(
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <persistence version="3.0" xmlns="https://jakarta.ee/xml/ns/persistence">
                    <persistence-unit name="InterceptorUnit">
                        <properties>
                            <property name="hibernate.ejb.interceptor" value="com.example.AuditInterceptor"/>
                        </properties>
                    </persistence-unit>
                </persistence>
                """,
                spec -> spec.path("src/main/resources/META-INF/persistence.xml")
            ),
            properties(
                null,
                """
                # Spring Boot Application Properties
                # Generated from persistence.xml migration

                # Migrated from persistence.xml (persistence-unit: InterceptorUnit)

                spring.jpa.properties.hibernate.session_factory.interceptor=com.example.AuditInterceptor
                """,
                spec -> spec.path("src/main/resources/application.properties")
            )
        );
    }

    @Test
    void idempotentMigrationDoesNotDuplicate() {
        // Test that running the recipe twice doesn't duplicate the content
        rewriteRun(
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <persistence version="3.0" xmlns="https://jakarta.ee/xml/ns/persistence">
                    <persistence-unit name="IdempotentUnit">
                        <properties>
                            <property name="hibernate.show_sql" value="true"/>
                        </properties>
                    </persistence-unit>
                </persistence>
                """,
                spec -> spec.path("src/main/resources/META-INF/persistence.xml")
            ),
            // Existing application.properties already has migration marker
            properties(
                """
                server.port=8080

                # ===== JPA Configuration (migrated from persistence.xml) =====
                # Migrated from persistence.xml (persistence-unit: IdempotentUnit)

                spring.jpa.show-sql=true
                """,
                spec -> spec.path("src/main/resources/application.properties")
            )
        );
    }
}
