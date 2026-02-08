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
import org.openrewrite.test.TypeValidation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.java.Assertions.java;

/**
 * Tests for {@link MigrateManagedBeanToNamed}.
 */
class MigrateManagedBeanToNamedTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateManagedBeanToNamed())
            .typeValidationOptions(TypeValidation.none())
            .cycles(1)
            .expectedCyclesThatMakeChanges(1)
            .parser(JavaParser.fromJavaVersion()
                .classpath("jakarta.faces-api", "jakarta.inject-api", "jakarta.enterprise.cdi-api"));
    }

    @DocumentExample
    @Test
    void migrateManagedBeanToNamed() {
        // Test: @ManagedBean -> @Named
        rewriteRun(
            java(
                """
                import jakarta.faces.bean.ManagedBean;
                import jakarta.enterprise.context.RequestScoped;

                @ManagedBean
                @RequestScoped
                public class MyBean {
                }
                """,
                source -> source.after(after -> {
                    assertThat(after)
                        .contains("@Named")
                        .contains("import jakarta.inject.Named")
                        .contains("@RequestScoped");
                    // @ManagedBean should be removed
                    assertThat(after).doesNotContain("@ManagedBean");
                    assertThat(after).doesNotContain("import jakarta.faces.bean.ManagedBean");
                    return after;
                })
            )
        );
    }

    @Test
    void migrateManagedBeanWithNameToNamed() {
        // Test: @ManagedBean(name="x") -> @Named("x")
        rewriteRun(
            java(
                """
                import jakarta.faces.bean.ManagedBean;
                import jakarta.enterprise.context.RequestScoped;

                @ManagedBean(name = "customName")
                @RequestScoped
                public class MyBean {
                }
                """,
                source -> source.after(after -> {
                    assertThat(after)
                        .contains("@Named(\"customName\")")
                        .contains("import jakarta.inject.Named")
                        .doesNotContain("@ManagedBean");
                    return after;
                })
            )
        );
    }

    @Test
    void migrateManagedBeanWithShorthandName() {
        // Test: @ManagedBean("x") -> @Named("x")
        rewriteRun(
            java(
                """
                import jakarta.faces.bean.ManagedBean;

                @ManagedBean("shortName")
                public class MyBean {
                }
                """,
                source -> source.after(after -> {
                    assertThat(after)
                        .contains("@Named(\"shortName\")")
                        .contains("import jakarta.inject.Named")
                        .doesNotContain("@ManagedBean");
                    return after;
                })
            )
        );
    }

    @Test
    void migrateManagedBeanWithEagerAddsMarker() {
        // Test: @ManagedBean(eager=true) -> @Named + @NeedsReview
        rewriteRun(
            java(
                """
                import jakarta.faces.bean.ManagedBean;
                import jakarta.enterprise.context.ApplicationScoped;

                @ManagedBean(eager = true)
                @ApplicationScoped
                public class StartupBean {
                }
                """,
                source -> source.after(after -> {
                    assertThat(after)
                        .contains("@Named")
                        .contains("@NeedsReview")
                        .contains("SEMANTIC_CHANGE")
                        .contains("ManagedBean(eager=true)")
                        .contains("import jakarta.inject.Named")
                        // Annotation @ManagedBean should not appear as a standalone annotation (only in originalCode string)
                        .doesNotContain("@ManagedBean(eager = true)")
                        .doesNotContain("import jakarta.faces.bean.ManagedBean");
                    return after;
                })
            )
        );
    }

    @Test
    void migrateManagedBeanWithEagerAndName() {
        // Test: @ManagedBean(name="x", eager=true) -> @Named("x") + @NeedsReview
        rewriteRun(
            java(
                """
                import jakarta.faces.bean.ManagedBean;
                import jakarta.enterprise.context.ApplicationScoped;

                @ManagedBean(name = "startup", eager = true)
                @ApplicationScoped
                public class StartupBean {
                }
                """,
                source -> source.after(after -> {
                    assertThat(after)
                        .contains("@Named(\"startup\")")
                        .contains("@NeedsReview")
                        .contains("SEMANTIC_CHANGE")
                        .contains("ManagedBean(eager=true)")
                        // Annotation @ManagedBean should not appear as a standalone annotation (only in originalCode)
                        .doesNotContain("@ManagedBean(name")
                        .doesNotContain("import jakarta.faces.bean.ManagedBean");
                    return after;
                })
            )
        );
    }

    @Test
    void blockMigrationWithManagedProperty() {
        // Test: @ManagedBean + @ManagedProperty -> NO migration, class-level marker
        rewriteRun(
            java(
                """
                import jakarta.faces.bean.ManagedBean;
                import jakarta.faces.bean.ManagedProperty;
                import jakarta.enterprise.context.RequestScoped;

                @ManagedBean
                @RequestScoped
                public class OrderBean {
                    @ManagedProperty("#{userBean}")
                    private Object user;
                }
                """,
                source -> source.after(after -> {
                    assertThat(after)
                        .contains("@NeedsReview")
                        .contains("MANUAL_MIGRATION")
                        .contains("@ManagedProperty requires manual CDI migration")
                        .contains("#{userBean}")
                        // @ManagedBean should NOT be removed (blocked by @ManagedProperty)
                        .contains("@ManagedBean")
                        .contains("@ManagedProperty");
                    return after;
                })
            )
        );
    }

    @Test
    void blockMigrationWithMultipleManagedProperties() {
        // Test: Multiple @ManagedProperty fields -> all listed in originalCode
        rewriteRun(
            java(
                """
                import jakarta.faces.bean.ManagedBean;
                import jakarta.faces.bean.ManagedProperty;

                @ManagedBean
                public class OrderBean {
                    @ManagedProperty("#{userBean}")
                    private Object user;

                    @ManagedProperty("#{param.orderId}")
                    private String orderId;
                }
                """,
                source -> source.after(after -> {
                    assertThat(after)
                        .contains("@NeedsReview")
                        .contains("MANUAL_MIGRATION")
                        .contains("#{userBean}")
                        .contains("#{param.orderId}")
                        // @ManagedBean should still be present
                        .contains("@ManagedBean");
                    return after;
                })
            )
        );
    }

    @Test
    void noChangeWhenNeedsReviewAlreadyPresent() {
        // Test: Already has @NeedsReview -> no change
        rewriteRun(
            spec -> spec.expectedCyclesThatMakeChanges(0),
            java(
                """
                import com.github.rewrite.ejb.annotations.NeedsReview;
                import jakarta.faces.bean.ManagedBean;

                import static com.github.rewrite.ejb.annotations.NeedsReview.Category.MANUAL_MIGRATION;

                @NeedsReview(reason = "test", category = MANUAL_MIGRATION, originalCode = "test", suggestedAction = "test")
                @ManagedBean
                public class AlreadyMarkedBean {
                }
                """
            )
        );
    }

    @Test
    void noChangeWithoutManagedBean() {
        // Test: No @ManagedBean -> no change
        rewriteRun(
            spec -> spec.expectedCyclesThatMakeChanges(0),
            java(
                """
                import jakarta.enterprise.context.RequestScoped;
                import jakarta.inject.Named;

                @Named
                @RequestScoped
                public class CdiBean {
                }
                """
            )
        );
    }

    @Test
    void blockMigrationWithSetterManagedProperty() {
        // Test: @ManagedProperty on setter method -> NO migration, class-level marker
        // JSF allows @ManagedProperty on setter methods, not just fields
        rewriteRun(
            java(
                """
                import jakarta.faces.bean.ManagedBean;
                import jakarta.faces.bean.ManagedProperty;
                import jakarta.enterprise.context.RequestScoped;

                @ManagedBean
                @RequestScoped
                public class SetterBean {
                    private Object user;

                    @ManagedProperty("#{userBean}")
                    public void setUser(Object user) {
                        this.user = user;
                    }

                    public Object getUser() {
                        return user;
                    }
                }
                """,
                source -> source.after(after -> {
                    assertThat(after)
                        .contains("@NeedsReview")
                        .contains("MANUAL_MIGRATION")
                        .contains("@ManagedProperty requires manual CDI migration")
                        .contains("#{userBean}")
                        // @ManagedBean should NOT be removed (blocked by @ManagedProperty)
                        .contains("@ManagedBean")
                        .contains("@ManagedProperty");
                    return after;
                })
            )
        );
    }

    @Test
    void blockMigrationWithMixedFieldAndSetterManagedProperty() {
        // Test: @ManagedProperty on both field and setter -> all listed in originalCode
        rewriteRun(
            java(
                """
                import jakarta.faces.bean.ManagedBean;
                import jakarta.faces.bean.ManagedProperty;

                @ManagedBean
                public class MixedBean {
                    @ManagedProperty("#{fieldBean}")
                    private Object fieldRef;

                    private Object setterRef;

                    @ManagedProperty("#{setterBean}")
                    public void setSetterRef(Object setterRef) {
                        this.setterRef = setterRef;
                    }
                }
                """,
                source -> source.after(after -> {
                    assertThat(after)
                        .contains("@NeedsReview")
                        .contains("MANUAL_MIGRATION")
                        .contains("#{fieldBean}")
                        .contains("#{setterBean}")
                        // @ManagedBean should still be present
                        .contains("@ManagedBean");
                    return after;
                })
            )
        );
    }

    // Note: javax.faces.bean.ManagedBean test is skipped because javax.faces-api is not available
    // as a classpath dependency. The recipe handles both namespaces via simple name matching.
}
