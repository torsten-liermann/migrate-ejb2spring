/*
 * Copyright 2021 - 2023 the original author or authors.
 * Modifications Copyright 2026 Torsten Liermann
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

/**
 * Tests for MigrateNamedToComponent recipe.
 * <p>
 * Verifies that CDI @Named annotations are properly migrated to Spring equivalents:
 * <ul>
 *   <li>@Named on classes becomes @Component</li>
 *   <li>@Named("name") on classes becomes @Component("name")</li>
 *   <li>@Named("qualifier") on injection points becomes @Qualifier("qualifier")</li>
 * </ul>
 * <p>
 * Note: Migration only occurs when strategy is "migrate-to-spring" (default is "keep-jsr330").
 * Tests use strategy override to test migration behavior.
 */
class MigrateNamedToComponentTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        // Use migrate-to-spring strategy to enable migration (default is keep-jsr330)
        spec.recipe(new MigrateNamedToComponent("migrate-to-spring"))
            .parser(JavaParser.fromJavaVersion()
                .classpath("jakarta.inject-api", "javax.inject", "spring-context", "spring-beans"));
    }

    @DocumentExample
    @Test
    void migratesClassLevelNamedWithoutValue() {
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.inject.Named;

                @Named
                public class MyService {
                }
                """,
                """
                package com.example;

                import org.springframework.stereotype.Component;

                @Component
                public class MyService {
                }
                """
            )
        );
    }

    @Test
    void migratesClassLevelNamedWithValue() {
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.inject.Named;

                @Named("myService")
                public class MyService {
                }
                """,
                """
                package com.example;

                import org.springframework.stereotype.Component;

                @Component("myService")
                public class MyService {
                }
                """
            )
        );
    }

    @Test
    void migratesJavaxNamedToComponent() {
        rewriteRun(
            java(
                """
                package com.example;

                import javax.inject.Named;

                @Named("legacyService")
                public class LegacyService {
                }
                """,
                """
                package com.example;

                import org.springframework.stereotype.Component;

                @Component("legacyService")
                public class LegacyService {
                }
                """
            )
        );
    }

    @Test
    void migratesFieldLevelNamedToQualifier() {
        rewriteRun(
            // Stub for MyService type resolution
            java(
                """
                package com.example;
                public interface MyService {}
                """
            ),
            java(
                """
                package com.example;

                import jakarta.inject.Inject;
                import jakarta.inject.Named;

                public class MyController {
                    @Inject
                    @Named("primaryService")
                    private MyService service;
                }
                """,
                """
                package com.example;

                import jakarta.inject.Inject;
                import org.springframework.beans.factory.annotation.Qualifier;

                public class MyController {
                    @Inject
                    @Qualifier("primaryService")
                    private MyService service;
                }
                """
            )
        );
    }

    @Test
    void removesFieldLevelNamedWithoutValue() {
        rewriteRun(
            // Stub for MyService type resolution
            java(
                """
                package com.example;
                public interface MyService {}
                """
            ),
            java(
                """
                package com.example;

                import jakarta.inject.Inject;
                import jakarta.inject.Named;

                public class MyController {
                    @Inject
                    @Named
                    private MyService service;
                }
                """,
                """
                package com.example;

                import jakarta.inject.Inject;

                public class MyController {
                    @Inject
                    private MyService service;
                }
                """
            )
        );
    }

    @Test
    void transfersNamedValueToStereotypeWithoutValue() {
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.inject.Named;
                import org.springframework.stereotype.Service;

                @Named("billing")
                @Service
                public class BillingService {
                }
                """,
                """
                package com.example;

                import org.springframework.stereotype.Service;

                @Service("billing")
                public class BillingService {
                }
                """
            )
        );
    }

    @Test
    void removesNamedWhenStereotypeAlreadyHasValue() {
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.inject.Named;
                import org.springframework.stereotype.Service;

                @Named("ignored")
                @Service("existingName")
                public class MyService {
                }
                """,
                """
                package com.example;

                import org.springframework.stereotype.Service;

                @Service("existingName")
                public class MyService {
                }
                """
            )
        );
    }

    @Test
    void migratesParameterLevelNamedToQualifier() {
        rewriteRun(
            // Stub for MyService type resolution
            java(
                """
                package com.example;
                public interface MyService {}
                """
            ),
            java(
                """
                package com.example;

                import jakarta.inject.Inject;
                import jakarta.inject.Named;

                public class MyController {
                    @Inject
                    public MyController(@Named("primaryService") MyService service) {
                    }
                }
                """,
                """
                package com.example;

                import jakarta.inject.Inject;
                import org.springframework.beans.factory.annotation.Qualifier;

                public class MyController {
                    @Inject
                    public MyController(@Qualifier("primaryService") MyService service) {
                    }
                }
                """
            )
        );
    }

    @Test
    void handlesMultipleNamedAnnotationsInClass() {
        rewriteRun(
            // Stub for OtherService type resolution
            java(
                """
                package com.example;
                public interface OtherService {}
                """
            ),
            java(
                """
                package com.example;

                import jakarta.inject.Inject;
                import jakarta.inject.Named;

                @Named("myService")
                public class MyService {
                    @Inject
                    @Named("dependency")
                    private OtherService other;
                }
                """,
                """
                package com.example;

                import jakarta.inject.Inject;
                import org.springframework.beans.factory.annotation.Qualifier;
                import org.springframework.stereotype.Component;

                @Component("myService")
                public class MyService {
                    @Inject
                    @Qualifier("dependency")
                    private OtherService other;
                }
                """
            )
        );
    }

    @Test
    void preservesNonLiteralValueOnClassLevel() {
        rewriteRun(
            // Stub for constant
            java(
                """
                package com.example;
                public class Constants {
                    public static final String BEAN_NAME = "billingService";
                }
                """
            ),
            java(
                """
                package com.example;

                import jakarta.inject.Named;

                @Named(Constants.BEAN_NAME)
                public class BillingService {
                }
                """,
                """
                package com.example;

                import org.springframework.stereotype.Component;

                @Component(Constants.BEAN_NAME)
                public class BillingService {
                }
                """
            )
        );
    }

    @Test
    void preservesNonLiteralValueOnFieldLevel() {
        rewriteRun(
            // Stub for constant
            java(
                """
                package com.example;
                public class Constants {
                    public static final String QUALIFIER = "primaryService";
                }
                """
            ),
            // Stub for MyService type resolution
            java(
                """
                package com.example;
                public interface MyService {}
                """
            ),
            java(
                """
                package com.example;

                import jakarta.inject.Inject;
                import jakarta.inject.Named;

                public class MyController {
                    @Inject
                    @Named(Constants.QUALIFIER)
                    private MyService service;
                }
                """,
                """
                package com.example;

                import jakarta.inject.Inject;
                import org.springframework.beans.factory.annotation.Qualifier;

                public class MyController {
                    @Inject
                    @Qualifier(Constants.QUALIFIER)
                    private MyService service;
                }
                """
            )
        );
    }

    @Test
    void preservesImportWhenMethodLevelNamedExists() {
        // Stub for MyBean type resolution
        rewriteRun(
            java(
                """
                package com.example;
                public class MyBean {}
                """
            ),
            java(
                """
                package com.example;

                import jakarta.inject.Named;

                @Named("myService")
                public class MyService {
                    @Named("producer")
                    public MyBean createBean() {
                        return new MyBean();
                    }
                }
                """,
                """
                package com.example;

                import jakarta.inject.Named;
                import org.springframework.stereotype.Component;

                @Component("myService")
                public class MyService {
                    @Named("producer")
                    public MyBean createBean() {
                        return new MyBean();
                    }
                }
                """
            )
        );
    }

    @Test
    void noMigrationWhenStrategyIsKeepJsr330() {
        // Test that no migration happens when strategy is keep-jsr330 (default)
        rewriteRun(
            spec -> spec.recipe(new MigrateNamedToComponent("keep-jsr330")),
            java(
                """
                package com.example;

                import jakarta.inject.Named;

                @Named("myService")
                public class MyService {
                }
                """
                // No expected change - @Named should be preserved
            )
        );
    }

    @Test
    void noMigrationWithDefaultStrategy() {
        // Test that default strategy (no explicit strategy) doesn't migrate
        // because default is KEEP_JSR330
        rewriteRun(
            spec -> spec.recipe(new MigrateNamedToComponent()),
            java(
                """
                package com.example;

                import jakarta.inject.Named;

                @Named("anotherService")
                public class AnotherService {
                }
                """
                // No expected change - default is keep-jsr330
            )
        );
    }
}
