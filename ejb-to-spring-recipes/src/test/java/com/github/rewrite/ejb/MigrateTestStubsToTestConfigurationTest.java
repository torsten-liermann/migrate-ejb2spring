package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class MigrateTestStubsToTestConfigurationTest implements RewriteTest {

    private static final String TEST_CONFIGURATION_STUB =
        """
        package org.springframework.boot.test.context;
        import java.lang.annotation.*;
        @Target(ElementType.TYPE)
        @Retention(RetentionPolicy.RUNTIME)
        public @interface TestConfiguration {}
        """;

    private static final String BEAN_STUB =
        """
        package org.springframework.context.annotation;
        import java.lang.annotation.*;
        @Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
        @Retention(RetentionPolicy.RUNTIME)
        public @interface Bean {}
        """;

    private static final String PRIMARY_STUB =
        """
        package org.springframework.context.annotation;
        import java.lang.annotation.*;
        @Target({ElementType.TYPE, ElementType.METHOD})
        @Retention(RetentionPolicy.RUNTIME)
        public @interface Primary {}
        """;

    private static final String IMPORT_STUB =
        """
        package org.springframework.context.annotation;
        import java.lang.annotation.*;
        @Target(ElementType.TYPE)
        @Retention(RetentionPolicy.RUNTIME)
        public @interface Import { Class<?>[] value(); }
        """;

    private static final String SPRING_BOOT_TEST_STUB =
        """
        package org.springframework.boot.test.context;
        import java.lang.annotation.*;
        @Target(ElementType.TYPE)
        @Retention(RetentionPolicy.RUNTIME)
        public @interface SpringBootTest {}
        """;

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateTestStubsToTestConfiguration())
            .parser(JavaParser.fromJavaVersion()
                .classpath("spring-context")
                .dependsOn(TEST_CONFIGURATION_STUB, BEAN_STUB, PRIMARY_STUB, IMPORT_STUB, SPRING_BOOT_TEST_STUB));
    }

    @Test
    @DocumentExample
    void removesComponentAndGeneratesTestConfigurationNamedAfterTest() {
        rewriteRun(
            // Input: Test stub with @Component
            java(
                """
                package org.example.test;

                import org.springframework.stereotype.Component;

                @Component
                public class ApplicationEventsStub {
                    public void onEvent(String event) {
                    }
                }
                """,
                // Expected: @Component removed
                """
                package org.example.test;


                public class ApplicationEventsStub {
                    public void onEvent(String event) {
                    }
                }
                """,
                spec -> spec.path("src/test/java/org/example/test/ApplicationEventsStub.java")
            ),
            // Test class that triggers config generation - must reference stub to trigger generation
            java(
                """
                package org.example.test;

                import org.springframework.boot.test.context.SpringBootTest;

                @SpringBootTest
                public class MyApplicationTest {
                    private ApplicationEventsStub stub; // Stub usage triggers config generation
                }
                """,
                """
                package org.example.test;

                import org.springframework.boot.test.context.SpringBootTest;
                import org.springframework.context.annotation.Import;

                @Import(MyApplicationTestConfiguration.class)
                @SpringBootTest
                public class MyApplicationTest {
                    private ApplicationEventsStub stub; // Stub usage triggers config generation
                }
                """,
                spec -> spec.path("src/test/java/org/example/test/MyApplicationTest.java")
            ),
            // Generated: MyApplicationTestConfiguration.java (name derived from test class)
            java(
                null,
                """
                package org.example.test;

                import org.springframework.boot.test.context.TestConfiguration;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Primary;

                @TestConfiguration
                public class MyApplicationTestConfiguration {

                    @Bean
                    @Primary
                    public ApplicationEventsStub applicationEventsStub() {
                        return new ApplicationEventsStub();
                    }

                }
                """,
                spec -> spec.path("src/test/java/org/example/test/MyApplicationTestConfiguration.java")
            )
        );
    }

    @Test
    void removesServiceAnnotation() {
        rewriteRun(
            java(
                """
                package org.example.test;

                import org.springframework.stereotype.Service;

                @Service
                public class UserServiceMock {
                    public String getUser(int id) {
                        return "Mock User";
                    }
                }
                """,
                """
                package org.example.test;


                public class UserServiceMock {
                    public String getUser(int id) {
                        return "Mock User";
                    }
                }
                """,
                spec -> spec.path("src/test/java/org/example/test/UserServiceMock.java")
            ),
            java(
                """
                package org.example.test;

                import org.springframework.boot.test.context.SpringBootTest;

                @SpringBootTest
                public class UserServiceTest {
                    private UserServiceMock mock; // Stub usage triggers config generation
                }
                """,
                """
                package org.example.test;

                import org.springframework.boot.test.context.SpringBootTest;
                import org.springframework.context.annotation.Import;

                @Import(UserServiceTestConfiguration.class)
                @SpringBootTest
                public class UserServiceTest {
                    private UserServiceMock mock; // Stub usage triggers config generation
                }
                """,
                spec -> spec.path("src/test/java/org/example/test/UserServiceTest.java")
            ),
            java(
                null,
                """
                package org.example.test;

                import org.springframework.boot.test.context.TestConfiguration;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Primary;

                @TestConfiguration
                public class UserServiceTestConfiguration {

                    @Bean
                    @Primary
                    public UserServiceMock userServiceMock() {
                        return new UserServiceMock();
                    }

                }
                """,
                spec -> spec.path("src/test/java/org/example/test/UserServiceTestConfiguration.java")
            )
        );
    }

    @Test
    void removesRepositoryAnnotation() {
        rewriteRun(
            java(
                """
                package org.example.test;

                import org.springframework.stereotype.Repository;

                @Repository
                public class DataRepositoryFake {
                    public Object find(int id) {
                        return "fake";
                    }
                }
                """,
                """
                package org.example.test;


                public class DataRepositoryFake {
                    public Object find(int id) {
                        return "fake";
                    }
                }
                """,
                spec -> spec.path("src/test/java/org/example/test/DataRepositoryFake.java")
            ),
            java(
                """
                package org.example.test;

                import org.springframework.boot.test.context.SpringBootTest;

                @SpringBootTest
                public class DataTest {
                    private DataRepositoryFake fake; // Stub usage triggers config generation
                }
                """,
                """
                package org.example.test;

                import org.springframework.boot.test.context.SpringBootTest;
                import org.springframework.context.annotation.Import;

                @Import(DataTestConfiguration.class)
                @SpringBootTest
                public class DataTest {
                    private DataRepositoryFake fake; // Stub usage triggers config generation
                }
                """,
                spec -> spec.path("src/test/java/org/example/test/DataTest.java")
            ),
            java(
                null,
                """
                package org.example.test;

                import org.springframework.boot.test.context.TestConfiguration;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Primary;

                @TestConfiguration
                public class DataTestConfiguration {

                    @Bean
                    @Primary
                    public DataRepositoryFake dataRepositoryFake() {
                        return new DataRepositoryFake();
                    }

                }
                """,
                spec -> spec.path("src/test/java/org/example/test/DataTestConfiguration.java")
            )
        );
    }

    @Test
    void handlesMultipleStubsInSamePackage() {
        rewriteRun(
            java(
                """
                package org.example.test;

                import org.springframework.stereotype.Component;

                @Component
                public class EventsStub {
                }
                """,
                """
                package org.example.test;


                public class EventsStub {
                }
                """,
                spec -> spec.path("src/test/java/org/example/test/EventsStub.java")
            ),
            java(
                """
                package org.example.test;

                import org.springframework.stereotype.Service;

                @Service
                public class ServiceMock {
                }
                """,
                """
                package org.example.test;


                public class ServiceMock {
                }
                """,
                spec -> spec.path("src/test/java/org/example/test/ServiceMock.java")
            ),
            java(
                """
                package org.example.test;

                import org.springframework.boot.test.context.SpringBootTest;

                @SpringBootTest
                public class IntegrationTest {
                    private EventsStub events; // Usage for EventsStub
                    private ServiceMock service; // Usage for ServiceMock
                }
                """,
                """
                package org.example.test;

                import org.springframework.boot.test.context.SpringBootTest;
                import org.springframework.context.annotation.Import;

                @Import(IntegrationTestConfiguration.class)
                @SpringBootTest
                public class IntegrationTest {
                    private EventsStub events; // Usage for EventsStub
                    private ServiceMock service; // Usage for ServiceMock
                }
                """,
                spec -> spec.path("src/test/java/org/example/test/IntegrationTest.java")
            ),
            // Generated config includes @NeedsReview comment for multiple @Primary beans
            java(
                null,
                """
                package org.example.test;

                import org.springframework.boot.test.context.TestConfiguration;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Primary;
                // @NeedsReview: Multiple @Primary beans - verify no type conflicts

                @TestConfiguration
                public class IntegrationTestConfiguration {

                    @Bean
                    @Primary
                    public EventsStub eventsStub() {
                        return new EventsStub();
                    }

                    @Bean
                    @Primary
                    public ServiceMock serviceMock() {
                        return new ServiceMock();
                    }

                }
                """,
                spec -> spec.path("src/test/java/org/example/test/IntegrationTestConfiguration.java")
            )
        );
    }

    @Test
    void doesNotModifyMainSourceClasses() {
        rewriteRun(
            java(
                """
                package org.example.app;

                import org.springframework.stereotype.Component;

                @Component
                public class ApplicationEventsStub {
                    public void onEvent(String event) {
                    }
                }
                """,
                spec -> spec.path("src/main/java/org/example/app/ApplicationEventsStub.java")
            )
        );
    }

    @Test
    void doesNotModifyTestClassWithoutStubPattern() {
        rewriteRun(
            java(
                """
                package org.example.test;

                import org.springframework.stereotype.Component;

                @Component
                public class TestHelper {
                    public void help() {
                    }
                }
                """,
                spec -> spec.path("src/test/java/org/example/test/TestHelper.java")
            )
        );
    }

    @Test
    void handlesConstructorWithParameters() {
        rewriteRun(
            java(
                """
                package org.example.test;

                import org.springframework.stereotype.Component;

                @Component
                public class DatabaseStub {
                    private final String connectionString;

                    public DatabaseStub(String connectionString) {
                        this.connectionString = connectionString;
                    }
                }
                """,
                """
                package org.example.test;


                public class DatabaseStub {
                    private final String connectionString;

                    public DatabaseStub(String connectionString) {
                        this.connectionString = connectionString;
                    }
                }
                """,
                spec -> spec.path("src/test/java/org/example/test/DatabaseStub.java")
            ),
            java(
                """
                package org.example.test;

                import org.springframework.boot.test.context.SpringBootTest;

                @SpringBootTest
                public class DatabaseTest {
                    private DatabaseStub db; // Stub usage triggers config generation
                }
                """,
                """
                package org.example.test;

                import org.springframework.boot.test.context.SpringBootTest;
                import org.springframework.context.annotation.Import;

                @Import(DatabaseTestConfiguration.class)
                @SpringBootTest
                public class DatabaseTest {
                    private DatabaseStub db; // Stub usage triggers config generation
                }
                """,
                spec -> spec.path("src/test/java/org/example/test/DatabaseTest.java")
            ),
            java(
                null,
                """
                package org.example.test;

                import org.springframework.boot.test.context.TestConfiguration;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Primary;

                @TestConfiguration
                public class DatabaseTestConfiguration {

                    @Bean
                    @Primary
                    public DatabaseStub databaseStub(String connectionString) {
                        return new DatabaseStub(connectionString);
                    }

                }
                """,
                spec -> spec.path("src/test/java/org/example/test/DatabaseTestConfiguration.java")
            )
        );
    }

    @Test
    void addsImportToSpringBootTestClass() {
        rewriteRun(
            // Stub that triggers config generation
            java(
                """
                package org.example.test;

                import org.springframework.stereotype.Component;

                @Component
                public class PaymentClientStub {
                }
                """,
                """
                package org.example.test;


                public class PaymentClientStub {
                }
                """,
                spec -> spec.path("src/test/java/org/example/test/PaymentClientStub.java")
            ),
            // Test class that should get @Import - references stub to trigger config generation
            java(
                """
                package org.example.test;

                import org.springframework.boot.test.context.SpringBootTest;

                @SpringBootTest
                public class PaymentServiceTest {
                    private PaymentClientStub paymentClient; // Stub usage triggers config generation
                    void testPayment() {
                    }
                }
                """,
                """
                package org.example.test;

                import org.springframework.boot.test.context.SpringBootTest;
                import org.springframework.context.annotation.Import;

                @Import(PaymentServiceTestConfiguration.class)
                @SpringBootTest
                public class PaymentServiceTest {
                    private PaymentClientStub paymentClient; // Stub usage triggers config generation
                    void testPayment() {
                    }
                }
                """,
                spec -> spec.path("src/test/java/org/example/test/PaymentServiceTest.java")
            ),
            // Generated config
            java(
                null,
                """
                package org.example.test;

                import org.springframework.boot.test.context.TestConfiguration;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Primary;

                @TestConfiguration
                public class PaymentServiceTestConfiguration {

                    @Bean
                    @Primary
                    public PaymentClientStub paymentClientStub() {
                        return new PaymentClientStub();
                    }

                }
                """,
                spec -> spec.path("src/test/java/org/example/test/PaymentServiceTestConfiguration.java")
            )
        );
    }

    @Test
    void mergesImportWithExistingSingleClass() {
        rewriteRun(
            // Stub that triggers config generation
            java(
                """
                package org.example.test;

                import org.springframework.stereotype.Component;

                @Component
                public class ServiceStub {
                }
                """,
                """
                package org.example.test;


                public class ServiceStub {
                }
                """,
                spec -> spec.path("src/test/java/org/example/test/ServiceStub.java")
            ),
            // Test class with existing @Import - should merge - references stub to trigger config
            java(
                """
                package org.example.test;

                import org.springframework.boot.test.context.SpringBootTest;
                import org.springframework.context.annotation.Import;

                @Import(SomeOtherConfig.class)
                @SpringBootTest
                public class MergeTest {
                    private ServiceStub service; // Stub usage triggers config generation
                }
                """,
                """
                package org.example.test;

                import org.springframework.boot.test.context.SpringBootTest;
                import org.springframework.context.annotation.Import;

                @Import({SomeOtherConfig.class, MergeTestConfiguration.class})
                @SpringBootTest
                public class MergeTest {
                    private ServiceStub service; // Stub usage triggers config generation
                }
                """,
                spec -> spec.path("src/test/java/org/example/test/MergeTest.java")
            ),
            // Stub for SomeOtherConfig (for parsing)
            java(
                """
                package org.example.test;
                public class SomeOtherConfig {}
                """,
                spec -> spec.path("src/test/java/org/example/test/SomeOtherConfig.java")
            ),
            // Generated config
            java(
                null,
                """
                package org.example.test;

                import org.springframework.boot.test.context.TestConfiguration;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Primary;

                @TestConfiguration
                public class MergeTestConfiguration {

                    @Bean
                    @Primary
                    public ServiceStub serviceStub() {
                        return new ServiceStub();
                    }

                }
                """,
                spec -> spec.path("src/test/java/org/example/test/MergeTestConfiguration.java")
            )
        );
    }

    @Test
    void mergesImportWithValueAttribute() {
        rewriteRun(
            // Stub that triggers config generation
            java(
                """
                package org.example.test;

                import org.springframework.stereotype.Component;

                @Component
                public class ApiClientStub {
                }
                """,
                """
                package org.example.test;


                public class ApiClientStub {
                }
                """,
                spec -> spec.path("src/test/java/org/example/test/ApiClientStub.java")
            ),
            // Test class with @Import(value = {...}) form - should merge correctly - references stub
            java(
                """
                package org.example.test;

                import org.springframework.boot.test.context.SpringBootTest;
                import org.springframework.context.annotation.Import;

                @Import(value = {SomeOtherConfig.class})
                @SpringBootTest
                public class ValueAttributeTest {
                    private ApiClientStub apiClient; // Stub usage triggers config generation
                }
                """,
                """
                package org.example.test;

                import org.springframework.boot.test.context.SpringBootTest;
                import org.springframework.context.annotation.Import;

                @Import({SomeOtherConfig.class, ValueAttributeTestConfiguration.class})
                @SpringBootTest
                public class ValueAttributeTest {
                    private ApiClientStub apiClient; // Stub usage triggers config generation
                }
                """,
                spec -> spec.path("src/test/java/org/example/test/ValueAttributeTest.java")
            ),
            // Stub for SomeOtherConfig (for parsing)
            java(
                """
                package org.example.test;
                public class SomeOtherConfig {}
                """,
                spec -> spec.path("src/test/java/org/example/test/SomeOtherConfig.java")
            ),
            // Generated config
            java(
                null,
                """
                package org.example.test;

                import org.springframework.boot.test.context.TestConfiguration;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Primary;

                @TestConfiguration
                public class ValueAttributeTestConfiguration {

                    @Bean
                    @Primary
                    public ApiClientStub apiClientStub() {
                        return new ApiClientStub();
                    }

                }
                """,
                spec -> spec.path("src/test/java/org/example/test/ValueAttributeTestConfiguration.java")
            )
        );
    }

    @Test
    void isolatesMultiModuleStubsBySourceRoot() {
        // Verifies that stubs with identical FQN in different source roots are handled independently
        rewriteRun(
            // Module A: Stub that triggers config generation in module-a
            java(
                """
                package org.example.test;

                import org.springframework.stereotype.Component;

                @Component
                public class ServiceStub {
                }
                """,
                """
                package org.example.test;


                public class ServiceStub {
                }
                """,
                spec -> spec.path("module-a/src/test/java/org/example/test/ServiceStub.java")
            ),
            // Module A: Test class should get @Import - references stub to trigger config
            java(
                """
                package org.example.test;

                import org.springframework.boot.test.context.SpringBootTest;

                @SpringBootTest
                public class ModuleATest {
                    private ServiceStub service; // Stub usage triggers config generation
                }
                """,
                """
                package org.example.test;

                import org.springframework.boot.test.context.SpringBootTest;
                import org.springframework.context.annotation.Import;

                @Import(ModuleATestConfiguration.class)
                @SpringBootTest
                public class ModuleATest {
                    private ServiceStub service; // Stub usage triggers config generation
                }
                """,
                spec -> spec.path("module-a/src/test/java/org/example/test/ModuleATest.java")
            ),
            // Module A: Generated config
            java(
                null,
                """
                package org.example.test;

                import org.springframework.boot.test.context.TestConfiguration;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Primary;

                @TestConfiguration
                public class ModuleATestConfiguration {

                    @Bean
                    @Primary
                    public ServiceStub serviceStub() {
                        return new ServiceStub();
                    }

                }
                """,
                spec -> spec.path("module-a/src/test/java/org/example/test/ModuleATestConfiguration.java")
            ),
            // Module B: Same FQN class but NOT a stub (no pattern match) - should NOT be modified
            java(
                """
                package org.example.test;

                import org.springframework.stereotype.Component;

                @Component
                public class ServiceHelper {
                }
                """,
                spec -> spec.path("module-b/src/test/java/org/example/test/ServiceHelper.java")
            ),
            // Module B: Test class should NOT get @Import (no stubs in this module)
            java(
                """
                package org.example.test;

                import org.springframework.boot.test.context.SpringBootTest;

                @SpringBootTest
                public class ModuleBTest {
                }
                """,
                spec -> spec.path("module-b/src/test/java/org/example/test/ModuleBTest.java")
            )
        );
    }

    @Test
    void generatesConfigPerTestClass() {
        // Two test classes in the same package should each get their own config
        rewriteRun(
            java(
                """
                package org.example.test;

                import org.springframework.stereotype.Component;

                @Component
                public class SharedStub {
                }
                """,
                """
                package org.example.test;


                public class SharedStub {
                }
                """,
                spec -> spec.path("src/test/java/org/example/test/SharedStub.java")
            ),
            // First test class - references stub to trigger config generation
            java(
                """
                package org.example.test;

                import org.springframework.boot.test.context.SpringBootTest;

                @SpringBootTest
                public class FirstTest {
                    private SharedStub stub; // Stub usage triggers config generation
                }
                """,
                """
                package org.example.test;

                import org.springframework.boot.test.context.SpringBootTest;
                import org.springframework.context.annotation.Import;

                @Import(FirstTestConfiguration.class)
                @SpringBootTest
                public class FirstTest {
                    private SharedStub stub; // Stub usage triggers config generation
                }
                """,
                spec -> spec.path("src/test/java/org/example/test/FirstTest.java")
            ),
            // Second test class - references stub to trigger config generation
            java(
                """
                package org.example.test;

                import org.springframework.boot.test.context.SpringBootTest;

                @SpringBootTest
                public class SecondTest {
                    private SharedStub stub; // Stub usage triggers config generation
                }
                """,
                """
                package org.example.test;

                import org.springframework.boot.test.context.SpringBootTest;
                import org.springframework.context.annotation.Import;

                @Import(SecondTestConfiguration.class)
                @SpringBootTest
                public class SecondTest {
                    private SharedStub stub; // Stub usage triggers config generation
                }
                """,
                spec -> spec.path("src/test/java/org/example/test/SecondTest.java")
            ),
            // Generated configs for each test
            java(
                null,
                """
                package org.example.test;

                import org.springframework.boot.test.context.TestConfiguration;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Primary;

                @TestConfiguration
                public class FirstTestConfiguration {

                    @Bean
                    @Primary
                    public SharedStub sharedStub() {
                        return new SharedStub();
                    }

                }
                """,
                spec -> spec.path("src/test/java/org/example/test/FirstTestConfiguration.java")
            ),
            java(
                null,
                """
                package org.example.test;

                import org.springframework.boot.test.context.TestConfiguration;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Primary;

                @TestConfiguration
                public class SecondTestConfiguration {

                    @Bean
                    @Primary
                    public SharedStub sharedStub() {
                        return new SharedStub();
                    }

                }
                """,
                spec -> spec.path("src/test/java/org/example/test/SecondTestConfiguration.java")
            )
        );
    }

    @Test
    void handlesConstructorWithGenericParameters() {
        // LOW fix: Test for generics import handling (List<Foo>, Map<String, Bar>)
        rewriteRun(
            // External dependency type for generic parameter
            java(
                """
                package org.example.domain;

                public class Order {
                    private String id;
                }
                """,
                spec -> spec.path("src/test/java/org/example/domain/Order.java")
            ),
            // Stub with List<Order> constructor parameter
            java(
                """
                package org.example.test;

                import org.springframework.stereotype.Component;
                import java.util.List;
                import org.example.domain.Order;

                @Component
                public class OrderRepositoryStub {
                    private final List<Order> initialOrders;

                    public OrderRepositoryStub(List<Order> initialOrders) {
                        this.initialOrders = initialOrders;
                    }
                }
                """,
                """
                package org.example.test;

                import java.util.List;
                import org.example.domain.Order;


                public class OrderRepositoryStub {
                    private final List<Order> initialOrders;

                    public OrderRepositoryStub(List<Order> initialOrders) {
                        this.initialOrders = initialOrders;
                    }
                }
                """,
                spec -> spec.path("src/test/java/org/example/test/OrderRepositoryStub.java")
            ),
            // Test class that triggers config generation - references stub
            java(
                """
                package org.example.test;

                import org.springframework.boot.test.context.SpringBootTest;

                @SpringBootTest
                public class OrderServiceTest {
                    private OrderRepositoryStub stub; // Stub usage triggers config generation
                }
                """,
                """
                package org.example.test;

                import org.springframework.boot.test.context.SpringBootTest;
                import org.springframework.context.annotation.Import;

                @Import(OrderServiceTestConfiguration.class)
                @SpringBootTest
                public class OrderServiceTest {
                    private OrderRepositoryStub stub; // Stub usage triggers config generation
                }
                """,
                spec -> spec.path("src/test/java/org/example/test/OrderServiceTest.java")
            ),
            // Generated config should include import for Order
            java(
                null,
                """
                package org.example.test;

                import org.springframework.boot.test.context.TestConfiguration;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Primary;
                import java.util.List;
                import org.example.domain.Order;

                @TestConfiguration
                public class OrderServiceTestConfiguration {

                    @Bean
                    @Primary
                    public OrderRepositoryStub orderRepositoryStub(List<Order> initialOrders) {
                        return new OrderRepositoryStub(initialOrders);
                    }

                }
                """,
                spec -> spec.path("src/test/java/org/example/test/OrderServiceTestConfiguration.java")
            )
        );
    }

    @Test
    void detectsStubUsageInConstructorParameters() {
        // Round 2 HIGH fix: Test that stub usage in constructor parameters is detected
        rewriteRun(
            java(
                """
                package org.example.test;

                import org.springframework.stereotype.Component;

                @Component
                public class AuditServiceStub {
                    public void log(String message) {}
                }
                """,
                """
                package org.example.test;


                public class AuditServiceStub {
                    public void log(String message) {}
                }
                """,
                spec -> spec.path("src/test/java/org/example/test/AuditServiceStub.java")
            ),
            // Test class that uses stub via constructor parameter
            java(
                """
                package org.example.test;

                import org.springframework.boot.test.context.SpringBootTest;

                @SpringBootTest
                public class ConstructorInjectionTest {
                    private final AuditServiceStub audit;

                    public ConstructorInjectionTest(AuditServiceStub audit) {
                        this.audit = audit;
                    }
                }
                """,
                """
                package org.example.test;

                import org.springframework.boot.test.context.SpringBootTest;
                import org.springframework.context.annotation.Import;

                @Import(ConstructorInjectionTestConfiguration.class)
                @SpringBootTest
                public class ConstructorInjectionTest {
                    private final AuditServiceStub audit;

                    public ConstructorInjectionTest(AuditServiceStub audit) {
                        this.audit = audit;
                    }
                }
                """,
                spec -> spec.path("src/test/java/org/example/test/ConstructorInjectionTest.java")
            ),
            // Generated config
            java(
                null,
                """
                package org.example.test;

                import org.springframework.boot.test.context.TestConfiguration;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Primary;

                @TestConfiguration
                public class ConstructorInjectionTestConfiguration {

                    @Bean
                    @Primary
                    public AuditServiceStub auditServiceStub() {
                        return new AuditServiceStub();
                    }

                }
                """,
                spec -> spec.path("src/test/java/org/example/test/ConstructorInjectionTestConfiguration.java")
            )
        );
    }
}
