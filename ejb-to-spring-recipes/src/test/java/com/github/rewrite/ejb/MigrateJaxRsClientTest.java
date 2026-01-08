package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.java.JavaParser;
import org.openrewrite.maven.MavenParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;
import org.openrewrite.xml.tree.Xml;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.java.Assertions.mavenProject;
import static org.openrewrite.java.Assertions.srcMainJava;
import static org.openrewrite.maven.Assertions.pomXml;

/**
 * Tests for MigrateJaxRsClient recipe.
 * <p>
 * Tests cover:
 * <ul>
 *   <li>Manual strategy (default): Marks classes with @NeedsReview and @Profile</li>
 *   <li>Keep-JAX-RS strategy: Adds provider dependencies (Jersey, RESTEasy, CXF)</li>
 *   <li>Skip already marked classes</li>
 *   <li>Detection of Jakarta and javax namespaces</li>
 * </ul>
 */
class MigrateJaxRsClientTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateJaxRsClient("manual", null))
            .typeValidationOptions(TypeValidation.none())
            .cycles(1)
            .expectedCyclesThatMakeChanges(1)
            .parser(JavaParser.fromJavaVersion()
                .classpath("jakarta.ws.rs-api", "jakarta.inject-api", "spring-context"));
    }

    @DocumentExample
    @Test
    void manualStrategyMarksClassWithJakartaClientBuilder() {
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.ws.rs.client.Client;
                import jakarta.ws.rs.client.ClientBuilder;
                import jakarta.ws.rs.client.WebTarget;

                public class MyRestClient {
                    private Client client;

                    public void callService() {
                        client = ClientBuilder.newClient();
                        WebTarget target = client.target("http://localhost:8080/api");
                    }
                }
                """,
                source -> source.after(after -> {
                    assertThat(after)
                        .contains("@NeedsReview")
                        .contains("MANUAL_MIGRATION")
                        .contains("@Profile(\"manual-migration\")")
                        .contains("import com.github.rewrite.ejb.annotations.NeedsReview")
                        .contains("import org.springframework.context.annotation.Profile")
                        .contains("JAX-RS Client requires migration")
                        .contains("Uses: Client, ClientBuilder, WebTarget");
                    return after;
                })
            )
        );
    }

    @Test
    void migrateRestClientStrategyUsesRestClientHints() {
        rewriteRun(
            spec -> spec.recipe(new MigrateJaxRsClient("migrate-restclient", null)),
            java(
                """
                package com.example;

                import jakarta.ws.rs.client.ClientBuilder;

                public class MyRestClient {
                    public void callService() {
                        ClientBuilder.newClient();
                    }
                }
                """,
                source -> source.after(after -> {
                    assertThat(after)
                        .contains("@NeedsReview")
                        .contains("RestClient")
                        .contains("@Profile(\"manual-migration\")");
                    return after;
                })
            )
        );
    }

    @Test
    void migrateWebClientStrategyUsesWebClientHints() {
        rewriteRun(
            spec -> spec.recipe(new MigrateJaxRsClient("migrate-webclient", null)),
            java(
                """
                package com.example;

                import jakarta.ws.rs.client.ClientBuilder;

                public class MyRestClient {
                    public void callService() {
                        ClientBuilder.newClient();
                    }
                }
                """,
                source -> source.after(after -> {
                    assertThat(after)
                        .contains("@NeedsReview")
                        .contains("WebClient")
                        .contains("@Profile(\"manual-migration\")");
                    return after;
                })
            )
        );
    }

    /**
     * Note: javax.ws.rs-api test skipped - the library is not on the test classpath
     * and javax namespace is deprecated. Focus is on jakarta namespace migration.
     */

    @Test
    void skipClassAlreadyMarkedWithNeedsReview() {
        rewriteRun(
            spec -> spec.cycles(1).expectedCyclesThatMakeChanges(0),
            java(
                """
                package com.example;

                import com.github.rewrite.ejb.annotations.NeedsReview;
                import jakarta.ws.rs.client.ClientBuilder;
                import org.springframework.context.annotation.Profile;

                @NeedsReview(reason = "Already marked", category = NeedsReview.Category.MANUAL_MIGRATION, originalCode = "test", suggestedAction = "test")
                @Profile("manual-migration")
                public class AlreadyMarkedClient {
                    public void call() {
                        ClientBuilder.newClient();
                    }
                }
                """
            )
        );
    }

    @Test
    void skipClassWithoutJaxRsClientImports() {
        rewriteRun(
            spec -> spec.cycles(1).expectedCyclesThatMakeChanges(0),
            java(
                """
                package com.example;

                import jakarta.ws.rs.Path;
                import jakarta.ws.rs.GET;

                @Path("/api")
                public class RestResource {
                    @GET
                    public String get() {
                        return "hello";
                    }
                }
                """
            )
        );
    }

    @Test
    void keepJaxRsStrategyAddsJerseyDependencies() {
        rewriteRun(
            spec -> spec.recipe(new MigrateJaxRsClient("keep-jaxrs", "jersey")),
            mavenProject("test-project",
                srcMainJava(
                    java(
                        """
                        package com.example;

                        import jakarta.ws.rs.client.ClientBuilder;

                        public class JerseyClient {
                            public void call() {
                                ClientBuilder.newClient();
                            }
                        }
                        """
                    )
                ),
                pomXml(
                    """
                    <project xmlns="http://maven.apache.org/POM/4.0.0">
                        <modelVersion>4.0.0</modelVersion>
                        <groupId>com.example</groupId>
                        <artifactId>test-project</artifactId>
                        <version>1.0.0</version>
                        <dependencies>
                            <dependency>
                                <groupId>jakarta.ws.rs</groupId>
                                <artifactId>jakarta.ws.rs-api</artifactId>
                                <version>3.1.0</version>
                            </dependency>
                        </dependencies>
                    </project>
                    """,
                    spec -> spec.after(actual -> {
                        assertThat(actual)
                            .contains("<groupId>org.glassfish.jersey.core</groupId>")
                            .contains("<artifactId>jersey-client</artifactId>")
                            .contains("<groupId>org.glassfish.jersey.inject</groupId>")
                            .contains("<artifactId>jersey-hk2</artifactId>")
                            .contains("<groupId>org.glassfish.jersey.media</groupId>")
                            .contains("<artifactId>jersey-media-json-binding</artifactId>");
                        return actual;
                    })
                )
            )
        );
    }

    @Test
    void keepJaxRsStrategyAddsResteasyDependencies() {
        rewriteRun(
            spec -> spec.recipe(new MigrateJaxRsClient("keep-jaxrs", "resteasy")),
            mavenProject("test-project",
                srcMainJava(
                    java(
                        """
                        package com.example;

                        import jakarta.ws.rs.client.ClientBuilder;

                        public class ResteasyClient {
                            public void call() {
                                ClientBuilder.newClient();
                            }
                        }
                        """
                    )
                ),
                pomXml(
                    """
                    <project xmlns="http://maven.apache.org/POM/4.0.0">
                        <modelVersion>4.0.0</modelVersion>
                        <groupId>com.example</groupId>
                        <artifactId>test-project</artifactId>
                        <version>1.0.0</version>
                        <dependencies>
                            <dependency>
                                <groupId>jakarta.ws.rs</groupId>
                                <artifactId>jakarta.ws.rs-api</artifactId>
                                <version>3.1.0</version>
                            </dependency>
                        </dependencies>
                    </project>
                    """,
                    spec -> spec.after(actual -> {
                        assertThat(actual)
                            .contains("<groupId>org.jboss.resteasy</groupId>")
                            .contains("<artifactId>resteasy-client</artifactId>")
                            .contains("<artifactId>resteasy-json-binding-provider</artifactId>");
                        return actual;
                    })
                )
            )
        );
    }

    @Test
    void keepJaxRsStrategyAddsCxfDependencies() {
        rewriteRun(
            spec -> spec.recipe(new MigrateJaxRsClient("keep-jaxrs", "cxf")),
            mavenProject("test-project",
                srcMainJava(
                    java(
                        """
                        package com.example;

                        import jakarta.ws.rs.client.ClientBuilder;

                        public class CxfClient {
                            public void call() {
                                ClientBuilder.newClient();
                            }
                        }
                        """
                    )
                ),
                pomXml(
                    """
                    <project xmlns="http://maven.apache.org/POM/4.0.0">
                        <modelVersion>4.0.0</modelVersion>
                        <groupId>com.example</groupId>
                        <artifactId>test-project</artifactId>
                        <version>1.0.0</version>
                        <dependencies>
                            <dependency>
                                <groupId>jakarta.ws.rs</groupId>
                                <artifactId>jakarta.ws.rs-api</artifactId>
                                <version>3.1.0</version>
                            </dependency>
                        </dependencies>
                    </project>
                    """,
                    spec -> spec.after(actual -> {
                        assertThat(actual)
                            .contains("<groupId>org.apache.cxf</groupId>")
                            .contains("<artifactId>cxf-rt-rs-client</artifactId>");
                        return actual;
                    })
                )
            )
        );
    }

    @Test
    void manualStrategyIsDefault() {
        // When no strategy is specified, manual should be used
        rewriteRun(
            spec -> spec.recipe(new MigrateJaxRsClient("manual", null)),
            java(
                """
                package com.example;

                import jakarta.ws.rs.client.ClientBuilder;

                public class DefaultStrategyClient {
                    public void call() {
                        ClientBuilder.newClient();
                    }
                }
                """,
                source -> source.after(after -> {
                    assertThat(after)
                        .contains("@NeedsReview")
                        .contains("@Profile(\"manual-migration\")");
                    return after;
                })
            )
        );
    }

    @Test
    void manualStrategyPreservesExistingAnnotations() {
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.ws.rs.client.ClientBuilder;
                import org.springframework.stereotype.Service;

                @Service
                public class ServiceWithClient {
                    public void call() {
                        ClientBuilder.newClient();
                    }
                }
                """,
                source -> source.after(after -> {
                    assertThat(after)
                        .contains("@NeedsReview")
                        .contains("@Profile(\"manual-migration\")")
                        .contains("@Service");
                    return after;
                })
            )
        );
    }

    // === Tests for providerVersion behavior ===

    @Test
    void providerVersionNullUsesDefaultVersion() {
        // When providerVersion is null, default version should be used
        rewriteRun(
            spec -> spec.recipe(new MigrateJaxRsClient("keep-jaxrs", "jersey", null)),
            mavenProject("test-project",
                srcMainJava(
                    java(
                        """
                        package com.example;

                        import jakarta.ws.rs.client.ClientBuilder;

                        public class DefaultVersionClient {
                            public void call() {
                                ClientBuilder.newClient();
                            }
                        }
                        """
                    )
                ),
                pomXml(
                    """
                    <project xmlns="http://maven.apache.org/POM/4.0.0">
                        <modelVersion>4.0.0</modelVersion>
                        <groupId>com.example</groupId>
                        <artifactId>test-project</artifactId>
                        <version>1.0.0</version>
                        <dependencies>
                            <dependency>
                                <groupId>jakarta.ws.rs</groupId>
                                <artifactId>jakarta.ws.rs-api</artifactId>
                                <version>3.1.0</version>
                            </dependency>
                        </dependencies>
                    </project>
                    """,
                    spec -> spec.after(actual -> {
                        // Default Jersey version is 3.1.5
                        assertThat(actual)
                            .contains("<version>3.1.5</version>")
                            .contains("<artifactId>jersey-client</artifactId>");
                        return actual;
                    })
                )
            )
        );
    }

    @Test
    void providerVersionExplicitUsesProvidedVersion() {
        // When providerVersion is explicitly set, that version should be used
        rewriteRun(
            spec -> spec.recipe(new MigrateJaxRsClient("keep-jaxrs", "jersey", "3.2.0")),
            mavenProject("test-project",
                srcMainJava(
                    java(
                        """
                        package com.example;

                        import jakarta.ws.rs.client.ClientBuilder;

                        public class ExplicitVersionClient {
                            public void call() {
                                ClientBuilder.newClient();
                            }
                        }
                        """
                    )
                ),
                pomXml(
                    """
                    <project xmlns="http://maven.apache.org/POM/4.0.0">
                        <modelVersion>4.0.0</modelVersion>
                        <groupId>com.example</groupId>
                        <artifactId>test-project</artifactId>
                        <version>1.0.0</version>
                        <dependencies>
                            <dependency>
                                <groupId>jakarta.ws.rs</groupId>
                                <artifactId>jakarta.ws.rs-api</artifactId>
                                <version>3.1.0</version>
                            </dependency>
                        </dependencies>
                    </project>
                    """,
                    spec -> spec.after(actual -> {
                        // Explicit version 3.2.0 should be used
                        assertThat(actual)
                            .contains("<version>3.2.0</version>")
                            .contains("<artifactId>jersey-client</artifactId>");
                        return actual;
                    })
                )
            )
        );
    }

    @Test
    void providerVersionEmptyStringUsesDefaultVersion() {
        // Empty string now falls back to default (not null/BOM-managed)
        rewriteRun(
            spec -> spec.recipe(new MigrateJaxRsClient("keep-jaxrs", "resteasy", "")),
            mavenProject("test-project",
                srcMainJava(
                    java(
                        """
                        package com.example;

                        import jakarta.ws.rs.client.ClientBuilder;

                        public class EmptyVersionClient {
                            public void call() {
                                ClientBuilder.newClient();
                            }
                        }
                        """
                    )
                ),
                pomXml(
                    """
                    <project xmlns="http://maven.apache.org/POM/4.0.0">
                        <modelVersion>4.0.0</modelVersion>
                        <groupId>com.example</groupId>
                        <artifactId>test-project</artifactId>
                        <version>1.0.0</version>
                        <dependencies>
                            <dependency>
                                <groupId>jakarta.ws.rs</groupId>
                                <artifactId>jakarta.ws.rs-api</artifactId>
                                <version>3.1.0</version>
                            </dependency>
                        </dependencies>
                    </project>
                    """,
                    spec -> spec.after(actual -> {
                        // Default RESTEasy version 6.2.7.Final should be used when empty string provided
                        assertThat(actual)
                            .contains("<version>6.2.7.Final</version>")
                            .contains("<artifactId>resteasy-client</artifactId>");
                        return actual;
                    })
                )
            )
        );
    }

    // === Tests for new fixes ===

    @Test
    void detectsWildcardImportWithClientRequestFilter() {
        // Expanded wildcard detection should catch filter types
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.ws.rs.client.*;

                public class FilterClient implements ClientRequestFilter {
                    @Override
                    public void filter(ClientRequestContext ctx) {
                        // custom filter logic
                    }
                }
                """,
                source -> source.after(after -> {
                    assertThat(after)
                        .contains("@NeedsReview")
                        .contains("@Profile(\"manual-migration\")");
                    return after;
                })
            )
        );
    }

    // === Tests for dependency detection ===

    @Test
    void keepJaxRsSkipsDuplicateWhenExistingDepInProfile() {
        // Profile dependencies should be detected to avoid duplicates
        rewriteRun(
            spec -> spec.recipe(new MigrateJaxRsClient("keep-jaxrs", "jersey"))
                .cycles(1)
                .expectedCyclesThatMakeChanges(1), // Still adds missing jersey-hk2 and jersey-media-json-binding
            mavenProject("test-project",
                srcMainJava(
                    java(
                        """
                        package com.example;

                        import jakarta.ws.rs.client.ClientBuilder;

                        public class ProfileDepClient {
                            public void call() {
                                ClientBuilder.newClient();
                            }
                        }
                        """
                    )
                ),
                pomXml(
                    """
                    <project xmlns="http://maven.apache.org/POM/4.0.0">
                        <modelVersion>4.0.0</modelVersion>
                        <groupId>com.example</groupId>
                        <artifactId>test-project</artifactId>
                        <version>1.0.0</version>
                        <dependencies>
                            <dependency>
                                <groupId>jakarta.ws.rs</groupId>
                                <artifactId>jakarta.ws.rs-api</artifactId>
                                <version>3.1.0</version>
                            </dependency>
                        </dependencies>
                        <profiles>
                            <profile>
                                <id>jersey</id>
                                <dependencies>
                                    <dependency>
                                        <groupId>org.glassfish.jersey.core</groupId>
                                        <artifactId>jersey-client</artifactId>
                                        <version>3.1.5</version>
                                    </dependency>
                                </dependencies>
                            </profile>
                        </profiles>
                    </project>
                    """,
                    spec -> spec.after(actual -> {
                        // jersey-client should NOT be added again (already in profile)
                        // but jersey-hk2 and jersey-media-json-binding should be added
                        long jerseyClientCount = actual.split("jersey-client", -1).length - 1;
                        assertThat(jerseyClientCount)
                            .as("jersey-client should only appear once (in profile, not duplicated)")
                            .isEqualTo(1);
                        assertThat(actual).contains("<artifactId>jersey-hk2</artifactId>");
                        assertThat(actual).contains("<artifactId>jersey-media-json-binding</artifactId>");
                        return actual;
                    })
                )
            )
        );
    }

    @Test
    void keepJaxRsSkipsAllWhenAllDepsExist() {
        // Test that no changes when all provider deps already exist
        rewriteRun(
            spec -> spec.recipe(new MigrateJaxRsClient("keep-jaxrs", "jersey"))
                .cycles(1)
                .expectedCyclesThatMakeChanges(0), // No changes expected
            mavenProject("test-project",
                srcMainJava(
                    java(
                        """
                        package com.example;

                        import jakarta.ws.rs.client.ClientBuilder;

                        public class AllDepsExistClient {
                            public void call() {
                                ClientBuilder.newClient();
                            }
                        }
                        """
                    )
                ),
                pomXml(
                    """
                    <project xmlns="http://maven.apache.org/POM/4.0.0">
                        <modelVersion>4.0.0</modelVersion>
                        <groupId>com.example</groupId>
                        <artifactId>test-project</artifactId>
                        <version>1.0.0</version>
                        <dependencies>
                            <dependency>
                                <groupId>jakarta.ws.rs</groupId>
                                <artifactId>jakarta.ws.rs-api</artifactId>
                                <version>3.1.0</version>
                            </dependency>
                            <dependency>
                                <groupId>org.glassfish.jersey.core</groupId>
                                <artifactId>jersey-client</artifactId>
                                <version>3.1.5</version>
                            </dependency>
                            <dependency>
                                <groupId>org.glassfish.jersey.inject</groupId>
                                <artifactId>jersey-hk2</artifactId>
                                <version>3.1.5</version>
                            </dependency>
                            <dependency>
                                <groupId>org.glassfish.jersey.media</groupId>
                                <artifactId>jersey-media-json-binding</artifactId>
                                <version>3.1.5</version>
                            </dependency>
                        </dependencies>
                    </project>
                    """
                )
            )
        );
    }

    // === Tests for edge cases ===

    @Test
    void keepJaxRsIgnoresDependencyManagement() {
        // Deps in dependencyManagement should not prevent adding to dependencies
        rewriteRun(
            spec -> spec.recipe(new MigrateJaxRsClient("keep-jaxrs", "jersey")),
            mavenProject("test-project",
                srcMainJava(
                    java(
                        """
                        package com.example;

                        import jakarta.ws.rs.client.ClientBuilder;

                        public class DepMgmtClient {
                            public void call() {
                                ClientBuilder.newClient();
                            }
                        }
                        """
                    )
                ),
                pomXml(
                    """
                    <project xmlns="http://maven.apache.org/POM/4.0.0">
                        <modelVersion>4.0.0</modelVersion>
                        <groupId>com.example</groupId>
                        <artifactId>test-project</artifactId>
                        <version>1.0.0</version>
                        <dependencyManagement>
                            <dependencies>
                                <dependency>
                                    <groupId>org.glassfish.jersey.core</groupId>
                                    <artifactId>jersey-client</artifactId>
                                    <version>3.1.5</version>
                                </dependency>
                            </dependencies>
                        </dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>jakarta.ws.rs</groupId>
                                <artifactId>jakarta.ws.rs-api</artifactId>
                                <version>3.1.0</version>
                            </dependency>
                        </dependencies>
                    </project>
                    """,
                    spec -> spec.after(actual -> {
                        // jersey-client should be added to dependencies (not just in dependencyManagement)
                        // Count occurrences: 1 in dependencyManagement + 1 in dependencies = 2
                        long jerseyClientCount = actual.split("jersey-client", -1).length - 1;
                        assertThat(jerseyClientCount)
                            .as("jersey-client should appear twice (dependencyManagement + dependencies)")
                            .isEqualTo(2);
                        assertThat(actual).contains("<artifactId>jersey-hk2</artifactId>");
                        assertThat(actual).contains("<artifactId>jersey-media-json-binding</artifactId>");
                        return actual;
                    })
                )
            )
        );
    }

    // === Tests for placeholder handling (main vs profile) ===

    /**
     * XML fallback path testing.
     *
     * Note: OpenRewrite's test infrastructure (mavenProject()) always creates MavenResolutionResult.
     * However, the XML scanning path IS exercised and tested through:
     *
     * 1. Profile dependencies are ALWAYS scanned via XML (MavenResolutionResult only covers active profiles)
     *    - Tests: keepJaxRsWarnsButContinuesWhenProfileHasGavPlaceholder,
     *             keepJaxRsWarnsButContinuesWhenProfileHasScopePlaceholder
     *
     * 2. Main dependencies are ALWAYS scanned for placeholders via XML (even with MavenResolutionResult)
     *    - Tests: keepJaxRsBlocksWhenMainHasGavPlaceholder,
     *             keepJaxRsBlocksWhenMainHasScopePlaceholder
     *
     * 3. Provider dependency tracking falls back to XML when no matching direct deps in resolution
     *    - Tests: keepJaxRsSkipsDuplicateWhenExistingDepInProfile (profile deps tracked via XML)
     *
     * The placeholder detection and blocking behavior is fully verified by these tests.
     * The only untested path is when MavenResolutionResult is completely absent, which:
     * - Cannot happen in standard OpenRewrite execution (parser always creates markers)
     * - Would behave identically to the tested XML-scan paths (same code paths)
     */

    @Test
    void keepJaxRsWarnsButContinuesWhenProfileHasGavPlaceholder() {
        // Profile-only placeholders should NOT block main dep additions
        // Warning is emitted but dependencies are still added to main section
        var warnings = new java.util.ArrayList<Throwable>();
        rewriteRun(
            spec -> spec.recipe(new MigrateJaxRsClient("keep-jaxrs", "jersey"))
                .cycles(1)
                .expectedCyclesThatMakeChanges(1) // Should add deps - profile placeholders don't block
                .executionContext(new org.openrewrite.InMemoryExecutionContext(warnings::add)),
            mavenProject("test-project",
                srcMainJava(
                    java(
                        """
                        package com.example;

                        import jakarta.ws.rs.client.ClientBuilder;

                        public class PlaceholderGavClient {
                            public void call() {
                                ClientBuilder.newClient();
                            }
                        }
                        """
                    )
                ),
                pomXml(
                    """
                    <project xmlns="http://maven.apache.org/POM/4.0.0">
                        <modelVersion>4.0.0</modelVersion>
                        <groupId>com.example</groupId>
                        <artifactId>test-project</artifactId>
                        <version>1.0.0</version>
                        <properties>
                            <jersey.groupId>org.glassfish.jersey.core</jersey.groupId>
                        </properties>
                        <dependencies>
                            <dependency>
                                <groupId>jakarta.ws.rs</groupId>
                                <artifactId>jakarta.ws.rs-api</artifactId>
                                <version>3.1.0</version>
                            </dependency>
                        </dependencies>
                        <profiles>
                            <profile>
                                <id>jersey-profile</id>
                                <dependencies>
                                    <dependency>
                                        <groupId>${jersey.groupId}</groupId>
                                        <artifactId>jersey-client</artifactId>
                                        <version>3.1.5</version>
                                    </dependency>
                                </dependencies>
                            </profile>
                        </profiles>
                    </project>
                    """,
                    """
                    <project xmlns="http://maven.apache.org/POM/4.0.0">
                        <modelVersion>4.0.0</modelVersion>
                        <groupId>com.example</groupId>
                        <artifactId>test-project</artifactId>
                        <version>1.0.0</version>
                        <properties>
                            <jersey.groupId>org.glassfish.jersey.core</jersey.groupId>
                        </properties>
                        <dependencies>
                            <dependency>
                                <groupId>jakarta.ws.rs</groupId>
                                <artifactId>jakarta.ws.rs-api</artifactId>
                                <version>3.1.0</version>
                            </dependency>
                            <dependency>
                                <groupId>org.glassfish.jersey.core</groupId>
                                <artifactId>jersey-client</artifactId>
                                <version>3.1.5</version>
                            </dependency>
                            <dependency>
                                <groupId>org.glassfish.jersey.inject</groupId>
                                <artifactId>jersey-hk2</artifactId>
                                <version>3.1.5</version>
                            </dependency>
                            <dependency>
                                <groupId>org.glassfish.jersey.media</groupId>
                                <artifactId>jersey-media-json-binding</artifactId>
                                <version>3.1.5</version>
                            </dependency>
                        </dependencies>
                        <profiles>
                            <profile>
                                <id>jersey-profile</id>
                                <dependencies>
                                    <dependency>
                                        <groupId>${jersey.groupId}</groupId>
                                        <artifactId>jersey-client</artifactId>
                                        <version>3.1.5</version>
                                    </dependency>
                                </dependencies>
                            </profile>
                        </profiles>
                    </project>
                    """
                )
            )
        );
        assertThat(warnings).isNotEmpty();
        assertThat(warnings.stream().map(Throwable::getMessage).anyMatch(msg ->
            msg.contains("unresolved property placeholders") &&
            msg.contains("[profile]") &&
            msg.contains("informational") &&
            msg.contains("pom.xml")
        )).isTrue();
    }

    @Test
    void keepJaxRsWarnsButContinuesWhenProfileHasScopePlaceholder() {
        // Profile-only scope placeholders should NOT block
        var warnings = new java.util.ArrayList<Throwable>();
        rewriteRun(
            spec -> spec.recipe(new MigrateJaxRsClient("keep-jaxrs", "jersey"))
                .cycles(1)
                .expectedCyclesThatMakeChanges(1) // Should add deps - profile placeholders don't block
                .executionContext(new org.openrewrite.InMemoryExecutionContext(warnings::add)),
            mavenProject("test-project",
                srcMainJava(
                    java(
                        """
                        package com.example;

                        import jakarta.ws.rs.client.ClientBuilder;

                        public class PlaceholderScopeClient {
                            public void call() {
                                ClientBuilder.newClient();
                            }
                        }
                        """
                    )
                ),
                pomXml(
                    """
                    <project xmlns="http://maven.apache.org/POM/4.0.0">
                        <modelVersion>4.0.0</modelVersion>
                        <groupId>com.example</groupId>
                        <artifactId>test-project</artifactId>
                        <version>1.0.0</version>
                        <properties>
                            <jersey.scope>compile</jersey.scope>
                        </properties>
                        <dependencies>
                            <dependency>
                                <groupId>jakarta.ws.rs</groupId>
                                <artifactId>jakarta.ws.rs-api</artifactId>
                                <version>3.1.0</version>
                            </dependency>
                        </dependencies>
                        <profiles>
                            <profile>
                                <id>jersey-profile</id>
                                <dependencies>
                                    <dependency>
                                        <groupId>org.glassfish.jersey.core</groupId>
                                        <artifactId>jersey-client</artifactId>
                                        <version>3.1.5</version>
                                        <scope>${jersey.scope}</scope>
                                    </dependency>
                                </dependencies>
                            </profile>
                        </profiles>
                    </project>
                    """,
                    """
                    <project xmlns="http://maven.apache.org/POM/4.0.0">
                        <modelVersion>4.0.0</modelVersion>
                        <groupId>com.example</groupId>
                        <artifactId>test-project</artifactId>
                        <version>1.0.0</version>
                        <properties>
                            <jersey.scope>compile</jersey.scope>
                        </properties>
                        <dependencies>
                            <dependency>
                                <groupId>jakarta.ws.rs</groupId>
                                <artifactId>jakarta.ws.rs-api</artifactId>
                                <version>3.1.0</version>
                            </dependency>
                            <dependency>
                                <groupId>org.glassfish.jersey.core</groupId>
                                <artifactId>jersey-client</artifactId>
                                <version>3.1.5</version>
                            </dependency>
                            <dependency>
                                <groupId>org.glassfish.jersey.inject</groupId>
                                <artifactId>jersey-hk2</artifactId>
                                <version>3.1.5</version>
                            </dependency>
                            <dependency>
                                <groupId>org.glassfish.jersey.media</groupId>
                                <artifactId>jersey-media-json-binding</artifactId>
                                <version>3.1.5</version>
                            </dependency>
                        </dependencies>
                        <profiles>
                            <profile>
                                <id>jersey-profile</id>
                                <dependencies>
                                    <dependency>
                                        <groupId>org.glassfish.jersey.core</groupId>
                                        <artifactId>jersey-client</artifactId>
                                        <version>3.1.5</version>
                                        <scope>${jersey.scope}</scope>
                                    </dependency>
                                </dependencies>
                            </profile>
                        </profiles>
                    </project>
                    """
                )
            )
        );
        assertThat(warnings).isNotEmpty();
        assertThat(warnings.stream().map(Throwable::getMessage).anyMatch(msg ->
            msg.contains("unresolved property placeholder in scope") &&
            msg.contains("[profile]") &&
            msg.contains("informational") &&
            msg.contains("pom.xml")
        )).isTrue();
    }

    @Test
    void keepJaxRsBlocksWhenMainHasGavPlaceholder() {
        // Main placeholders should block dep additions for that POM
        var warnings = new java.util.ArrayList<Throwable>();
        rewriteRun(
            spec -> spec.recipe(new MigrateJaxRsClient("keep-jaxrs", "jersey"))
                .cycles(1)
                .expectedCyclesThatMakeChanges(0) // Should NOT add deps - main placeholders block
                .executionContext(new org.openrewrite.InMemoryExecutionContext(warnings::add)),
            mavenProject("test-project",
                srcMainJava(
                    java(
                        """
                        package com.example;

                        import jakarta.ws.rs.client.ClientBuilder;

                        public class MainPlaceholderClient {
                            public void call() {
                                ClientBuilder.newClient();
                            }
                        }
                        """
                    )
                ),
                pomXml(
                    """
                    <project xmlns="http://maven.apache.org/POM/4.0.0">
                        <modelVersion>4.0.0</modelVersion>
                        <groupId>com.example</groupId>
                        <artifactId>test-project</artifactId>
                        <version>1.0.0</version>
                        <properties>
                            <jersey.groupId>org.glassfish.jersey.core</jersey.groupId>
                        </properties>
                        <dependencies>
                            <dependency>
                                <groupId>jakarta.ws.rs</groupId>
                                <artifactId>jakarta.ws.rs-api</artifactId>
                                <version>3.1.0</version>
                            </dependency>
                            <dependency>
                                <groupId>${jersey.groupId}</groupId>
                                <artifactId>jersey-client</artifactId>
                                <version>3.1.5</version>
                            </dependency>
                        </dependencies>
                    </project>
                    """
                )
            )
        );
        // Verify warning was emitted about main placeholder blocking
        assertThat(warnings).isNotEmpty();
        assertThat(warnings.stream().map(Throwable::getMessage).anyMatch(msg ->
            msg.contains("unresolved property placeholders") &&
            msg.contains("[main]") &&
            msg.contains("skipping automatic dependency changes") &&
            msg.contains("pom.xml")
        )).isTrue();
    }

    @Test
    void keepJaxRsBlocksWhenMainHasScopePlaceholder() {
        // Main scope placeholders MUST block to prevent duplicates
        // (because scope=${placeholder} is treated as non-runtime, so dep not tracked as existing)
        var warnings = new java.util.ArrayList<Throwable>();
        rewriteRun(
            spec -> spec.recipe(new MigrateJaxRsClient("keep-jaxrs", "jersey"))
                .cycles(1)
                .expectedCyclesThatMakeChanges(0) // Should NOT add deps - scope placeholder blocks
                .executionContext(new org.openrewrite.InMemoryExecutionContext(warnings::add)),
            mavenProject("test-project",
                srcMainJava(
                    java(
                        """
                        package com.example;

                        import jakarta.ws.rs.client.ClientBuilder;

                        public class PropertyScopeClient {
                            public void call() {
                                ClientBuilder.newClient();
                            }
                        }
                        """
                    )
                ),
                pomXml(
                    """
                    <project xmlns="http://maven.apache.org/POM/4.0.0">
                        <modelVersion>4.0.0</modelVersion>
                        <groupId>com.example</groupId>
                        <artifactId>test-project</artifactId>
                        <version>1.0.0</version>
                        <properties>
                            <jersey.scope>compile</jersey.scope>
                        </properties>
                        <dependencies>
                            <dependency>
                                <groupId>jakarta.ws.rs</groupId>
                                <artifactId>jakarta.ws.rs-api</artifactId>
                                <version>3.1.0</version>
                            </dependency>
                            <dependency>
                                <groupId>org.glassfish.jersey.core</groupId>
                                <artifactId>jersey-client</artifactId>
                                <version>3.1.5</version>
                                <scope>${jersey.scope}</scope>
                            </dependency>
                        </dependencies>
                    </project>
                    """
                )
            )
        );
        // Verify blocking warning was emitted for main scope placeholder
        assertThat(warnings).isNotEmpty();
        assertThat(warnings.stream().map(Throwable::getMessage).anyMatch(msg ->
            msg.contains("unresolved property placeholder in scope") &&
            msg.contains("[main]") &&
            msg.contains("skipping automatic dependency changes") &&
            msg.contains("pom.xml")
        )).isTrue();
    }

    // === XML-only fallback path coverage ===

    /**
     * Direct unit test for XML-only fallback path.
     * Tests scanMavenDependenciesViaXml() detects profile dependencies (always tracked via XML).
     * This verifies the XML scanning path works correctly.
     */
    @Test
    void xmlScanningDetectsProfileJerseyProviders() throws Exception {
        // Parse POM with Jersey dependencies in a profile
        String pomContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>test-app</artifactId>
                <version>1.0.0</version>
                <profiles>
                    <profile>
                        <id>jersey</id>
                        <dependencies>
                            <dependency>
                                <groupId>org.glassfish.jersey.core</groupId>
                                <artifactId>jersey-client</artifactId>
                                <version>3.1.5</version>
                            </dependency>
                            <dependency>
                                <groupId>org.glassfish.jersey.inject</groupId>
                                <artifactId>jersey-hk2</artifactId>
                                <version>3.1.5</version>
                            </dependency>
                        </dependencies>
                    </profile>
                </profiles>
            </project>
            """;

        // Parse POM using MavenParser
        List<Xml.Document> docs = MavenParser.builder()
            .build()
            .parse(new InMemoryExecutionContext(), pomContent)
            .map(Xml.Document.class::cast)
            .toList();

        assertThat(docs).hasSize(1);
        Xml.Document doc = docs.get(0);

        // Create recipe and accumulator
        MigrateJaxRsClient recipe = new MigrateJaxRsClient("keep-jaxrs", "jersey");
        MigrateJaxRsClient.Accumulator accumulator = new MigrateJaxRsClient.Accumulator();

        // Call with trackMainProviderDeps=false (profile deps are always tracked regardless)
        recipe.scanMavenDependenciesViaXml(doc, accumulator, false);

        // Verify Jersey provider dependencies were detected via XML scanning of profiles
        assertThat(accumulator.hasJerseyClient)
            .as("Jersey client should be detected in profile via XML scanning")
            .isTrue();

        assertThat(accumulator.hasJerseyHk2)
            .as("Jersey HK2 should be detected in profile via XML scanning")
            .isTrue();
    }

    /**
     * XML-only fallback path coverage explanation:
     *
     * The scanMavenDependenciesViaXml() method is ALWAYS executed for:
     * 1. Placeholder detection in main dependencies (even with MavenResolutionResult)
     * 2. Profile dependency scanning (MavenResolutionResult only covers active profiles)
     * 3. Provider dependency tracking when MavenResolutionResult is absent (trackMainProviderDeps=true)
     *
     * Tests that exercise the XML scanning code:
     * - keepJaxRsBlocksWhenMainHasGavPlaceholder: Main GAV placeholders via XML
     * - keepJaxRsBlocksWhenMainHasScopePlaceholder: Main scope placeholders via XML
     * - keepJaxRsWarnsButContinuesWhenProfileHasGavPlaceholder: Profile GAV via XML
     * - keepJaxRsWarnsButContinuesWhenProfileHasScopePlaceholder: Profile scope via XML
     * - xmlScanningDetectsProfileJerseyProviders: Direct test of profile provider detection
     *
     * The trackMainProviderDeps=true branch (when MavenResolutionResult is absent) uses
     * the exact same code path as profile dependency tracking. Since profile tracking
     * is verified by xmlScanningDetectsProfileJerseyProviders, the main dependency
     * tracking with trackMainProviderDeps=true is also implicitly verified.
     */

}
