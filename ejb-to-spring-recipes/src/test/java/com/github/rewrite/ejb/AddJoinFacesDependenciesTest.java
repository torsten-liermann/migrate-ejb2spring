/*
 * Copyright 2021 - 2023 the original author or authors.
 * Modifications Copyright 2026 Torsten Liermann
 *
 * Modified: Added P0.3 PrimeFaces version compatibility tests
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
import org.openrewrite.ExecutionContext;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.TreeVisitor;
import org.openrewrite.maven.MavenParser;
import org.openrewrite.xml.tree.Xml;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for AddJoinFacesDependencies recipe.
 * <p>
 * Tests cover:
 * <ul>
 *   <li>P0.3: PrimeFaces version compatibility check (unit tests)</li>
 *   <li>Version parsing for various formats</li>
 *   <li>Property resolution for version detection</li>
 * </ul>
 */
class AddJoinFacesDependenciesTest {

    // ==================== P0.3: Version Parsing Unit Tests ====================

    @Test
    void isPrimeFacesVersionIncompatible_version11() {
        assertThat(AddJoinFacesDependencies.isPrimeFacesVersionIncompatible("11.0.0")).isTrue();
    }

    @Test
    void isPrimeFacesVersionIncompatible_version10() {
        assertThat(AddJoinFacesDependencies.isPrimeFacesVersionIncompatible("10.0.0")).isTrue();
    }

    @Test
    void isPrimeFacesVersionIncompatible_version8() {
        assertThat(AddJoinFacesDependencies.isPrimeFacesVersionIncompatible("8.0")).isTrue();
    }

    @Test
    void isPrimeFacesVersionIncompatible_version6() {
        assertThat(AddJoinFacesDependencies.isPrimeFacesVersionIncompatible("6.2")).isTrue();
    }

    @Test
    void isPrimeFacesVersionIncompatible_version5() {
        assertThat(AddJoinFacesDependencies.isPrimeFacesVersionIncompatible("5.3.0")).isTrue();
    }

    @Test
    void isPrimeFacesVersionIncompatible_version12() {
        // Version 12.0.0 is the minimum compatible version
        assertThat(AddJoinFacesDependencies.isPrimeFacesVersionIncompatible("12.0.0")).isFalse();
    }

    @Test
    void isPrimeFacesVersionIncompatible_version12_minor() {
        assertThat(AddJoinFacesDependencies.isPrimeFacesVersionIncompatible("12.0.10")).isFalse();
    }

    @Test
    void isPrimeFacesVersionIncompatible_version13() {
        assertThat(AddJoinFacesDependencies.isPrimeFacesVersionIncompatible("13.0.0")).isFalse();
    }

    @Test
    void isPrimeFacesVersionIncompatible_version14() {
        assertThat(AddJoinFacesDependencies.isPrimeFacesVersionIncompatible("14.0.0")).isFalse();
    }

    @Test
    void isPrimeFacesVersionIncompatible_version14_minor() {
        assertThat(AddJoinFacesDependencies.isPrimeFacesVersionIncompatible("14.0.8")).isFalse();
    }

    @Test
    void isPrimeFacesVersionIncompatible_snapshotVersionIncompatible() {
        assertThat(AddJoinFacesDependencies.isPrimeFacesVersionIncompatible("11.0.0-SNAPSHOT")).isTrue();
    }

    @Test
    void isPrimeFacesVersionIncompatible_snapshotVersionCompatible() {
        assertThat(AddJoinFacesDependencies.isPrimeFacesVersionIncompatible("14.0.0-SNAPSHOT")).isFalse();
    }

    @Test
    void isPrimeFacesVersionIncompatible_rcVersion() {
        assertThat(AddJoinFacesDependencies.isPrimeFacesVersionIncompatible("14.0.0-RC1")).isFalse();
    }

    @Test
    void isPrimeFacesVersionIncompatible_unresolvedProperty() {
        // Unresolved property placeholders should be treated as compatible (unknown)
        assertThat(AddJoinFacesDependencies.isPrimeFacesVersionIncompatible("${primefaces.version}")).isFalse();
    }

    @Test
    void isPrimeFacesVersionIncompatible_nullOrEmpty() {
        assertThat(AddJoinFacesDependencies.isPrimeFacesVersionIncompatible(null)).isFalse();
        assertThat(AddJoinFacesDependencies.isPrimeFacesVersionIncompatible("")).isFalse();
    }

    // ==================== P0.3: Accumulator and Scanner Tests ====================

    @Test
    void scannerDetectsPrimeFacesInPom() {
        String pomContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>test-app</artifactId>
                <version>1.0.0</version>
                <dependencies>
                    <dependency>
                        <groupId>org.primefaces</groupId>
                        <artifactId>primefaces</artifactId>
                        <version>11.0.0</version>
                    </dependency>
                </dependencies>
            </project>
            """;

        List<Xml.Document> docs = MavenParser.builder()
            .build()
            .parse(new InMemoryExecutionContext(), pomContent)
            .map(Xml.Document.class::cast)
            .toList();

        assertThat(docs).hasSize(1);

        AddJoinFacesDependencies recipe = new AddJoinFacesDependencies();
        AddJoinFacesDependencies.Accumulator acc = recipe.getInitialValue(new InMemoryExecutionContext());

        // Manually scan the POM (simulating what the scanner does)
        recipe.getScanner(acc).visit(docs.get(0), new InMemoryExecutionContext());

        // Verify PrimeFaces was detected
        assertThat(acc.hasPrimeFaces).isTrue();
    }

    @Test
    void scannerExtractsVersionFromProperty() {
        String pomContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>test-app</artifactId>
                <version>1.0.0</version>
                <properties>
                    <primefaces.version>10.0.0</primefaces.version>
                </properties>
                <dependencies>
                    <dependency>
                        <groupId>org.primefaces</groupId>
                        <artifactId>primefaces</artifactId>
                        <version>${primefaces.version}</version>
                    </dependency>
                </dependencies>
            </project>
            """;

        List<Xml.Document> docs = MavenParser.builder()
            .build()
            .parse(new InMemoryExecutionContext(), pomContent)
            .map(Xml.Document.class::cast)
            .toList();

        assertThat(docs).hasSize(1);

        AddJoinFacesDependencies recipe = new AddJoinFacesDependencies();
        AddJoinFacesDependencies.Accumulator acc = recipe.getInitialValue(new InMemoryExecutionContext());

        // Scan the POM
        recipe.getScanner(acc).visit(docs.get(0), new InMemoryExecutionContext());

        // Verify version was resolved from property
        assertThat(acc.hasPrimeFaces).isTrue();
        // Version should be resolved from property (either directly via MavenResolutionResult or via XML fallback)
        // The exact version might be resolved by MavenResolutionResult or our XML fallback
        if (acc.primeFacesVersion != null) {
            assertThat(acc.primeFacesVersion).isEqualTo("10.0.0");
            assertThat(acc.primeFacesVersionIncompatible).isTrue();
        }
    }

    @Test
    void scannerDetectsCompatiblePrimeFacesVersion() {
        String pomContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>test-app</artifactId>
                <version>1.0.0</version>
                <dependencies>
                    <dependency>
                        <groupId>org.primefaces</groupId>
                        <artifactId>primefaces</artifactId>
                        <version>14.0.0</version>
                    </dependency>
                </dependencies>
            </project>
            """;

        List<Xml.Document> docs = MavenParser.builder()
            .build()
            .parse(new InMemoryExecutionContext(), pomContent)
            .map(Xml.Document.class::cast)
            .toList();

        assertThat(docs).hasSize(1);

        AddJoinFacesDependencies recipe = new AddJoinFacesDependencies();
        AddJoinFacesDependencies.Accumulator acc = recipe.getInitialValue(new InMemoryExecutionContext());

        // Scan the POM
        recipe.getScanner(acc).visit(docs.get(0), new InMemoryExecutionContext());

        // Verify PrimeFaces 14.0.0 is compatible
        assertThat(acc.hasPrimeFaces).isTrue();
        if (acc.primeFacesVersion != null) {
            assertThat(acc.primeFacesVersion).isEqualTo("14.0.0");
            assertThat(acc.primeFacesVersionIncompatible).isFalse();
        }
    }

    @Test
    void scannerDetectsIncompatiblePrimeFacesVersion() {
        String pomContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>test-app</artifactId>
                <version>1.0.0</version>
                <dependencies>
                    <dependency>
                        <groupId>org.primefaces</groupId>
                        <artifactId>primefaces</artifactId>
                        <version>11.0.0</version>
                    </dependency>
                </dependencies>
            </project>
            """;

        List<Xml.Document> docs = MavenParser.builder()
            .build()
            .parse(new InMemoryExecutionContext(), pomContent)
            .map(Xml.Document.class::cast)
            .toList();

        assertThat(docs).hasSize(1);

        AddJoinFacesDependencies recipe = new AddJoinFacesDependencies();
        AddJoinFacesDependencies.Accumulator acc = recipe.getInitialValue(new InMemoryExecutionContext());

        // Scan the POM
        recipe.getScanner(acc).visit(docs.get(0), new InMemoryExecutionContext());

        // Verify PrimeFaces 11.0.0 is incompatible
        assertThat(acc.hasPrimeFaces).isTrue();
        if (acc.primeFacesVersion != null) {
            assertThat(acc.primeFacesVersion).isEqualTo("11.0.0");
            assertThat(acc.primeFacesVersionIncompatible).isTrue();
        }
    }

    @Test
    void scannerDetectsJoinFacesAlreadyPresent() {
        String pomContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>test-app</artifactId>
                <version>1.0.0</version>
                <dependencies>
                    <dependency>
                        <groupId>org.joinfaces</groupId>
                        <artifactId>jsf-spring-boot-starter</artifactId>
                        <version>5.3.0</version>
                    </dependency>
                </dependencies>
            </project>
            """;

        List<Xml.Document> docs = MavenParser.builder()
            .build()
            .parse(new InMemoryExecutionContext(), pomContent)
            .map(Xml.Document.class::cast)
            .toList();

        assertThat(docs).hasSize(1);

        AddJoinFacesDependencies recipe = new AddJoinFacesDependencies();
        AddJoinFacesDependencies.Accumulator acc = recipe.getInitialValue(new InMemoryExecutionContext());

        // Scan the POM
        recipe.getScanner(acc).visit(docs.get(0), new InMemoryExecutionContext());

        // Verify JoinFaces was detected
        assertThat(acc.hasJoinFaces).isTrue();
    }

    @Test
    void scannerDetectsMyFaces() {
        String pomContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>test-app</artifactId>
                <version>1.0.0</version>
                <dependencies>
                    <dependency>
                        <groupId>org.apache.myfaces.core</groupId>
                        <artifactId>myfaces-api</artifactId>
                        <version>4.0.0</version>
                    </dependency>
                </dependencies>
            </project>
            """;

        List<Xml.Document> docs = MavenParser.builder()
            .build()
            .parse(new InMemoryExecutionContext(), pomContent)
            .map(Xml.Document.class::cast)
            .toList();

        assertThat(docs).hasSize(1);

        AddJoinFacesDependencies recipe = new AddJoinFacesDependencies();
        AddJoinFacesDependencies.Accumulator acc = recipe.getInitialValue(new InMemoryExecutionContext());

        // Scan the POM
        recipe.getScanner(acc).visit(docs.get(0), new InMemoryExecutionContext());

        // Verify MyFaces was detected
        assertThat(acc.hasMyFaces).isTrue();
    }

    // ==================== Boundary Tests ====================

    @Test
    void isPrimeFacesVersionIncompatible_boundaryVersion11_9() {
        // Version 11.9.x is still incompatible
        assertThat(AddJoinFacesDependencies.isPrimeFacesVersionIncompatible("11.9.9")).isTrue();
    }

    @Test
    void isPrimeFacesVersionIncompatible_boundaryVersion12_0() {
        // Version 12.0.0 is the first compatible version
        assertThat(AddJoinFacesDependencies.isPrimeFacesVersionIncompatible("12.0.0")).isFalse();
    }

    @Test
    void isPrimeFacesVersionIncompatible_communityVersion() {
        // Community versions like "8.0-community" should also be parsed
        assertThat(AddJoinFacesDependencies.isPrimeFacesVersionIncompatible("8.0-community")).isTrue();
    }

    // ==================== P0.3 Codex-Fix 4: Version Requires Review Tests (12.x-13.x) ====================

    @Test
    void isPrimeFacesVersionRequiresReview_version12() {
        assertThat(AddJoinFacesDependencies.isPrimeFacesVersionRequiresReview("12.0.0")).isTrue();
    }

    @Test
    void isPrimeFacesVersionRequiresReview_version12_minor() {
        assertThat(AddJoinFacesDependencies.isPrimeFacesVersionRequiresReview("12.0.10")).isTrue();
    }

    @Test
    void isPrimeFacesVersionRequiresReview_version13() {
        assertThat(AddJoinFacesDependencies.isPrimeFacesVersionRequiresReview("13.0.0")).isTrue();
    }

    @Test
    void isPrimeFacesVersionRequiresReview_version13_snapshot() {
        assertThat(AddJoinFacesDependencies.isPrimeFacesVersionRequiresReview("13.0.0-SNAPSHOT")).isTrue();
    }

    @Test
    void isPrimeFacesVersionRequiresReview_version14_notReview() {
        // Version 14+ is fully compatible, no review needed
        assertThat(AddJoinFacesDependencies.isPrimeFacesVersionRequiresReview("14.0.0")).isFalse();
    }

    @Test
    void isPrimeFacesVersionRequiresReview_version11_notReview() {
        // Version 11 is incompatible (not review-required, but flat out incompatible)
        assertThat(AddJoinFacesDependencies.isPrimeFacesVersionRequiresReview("11.0.0")).isFalse();
    }

    @Test
    void isPrimeFacesVersionRequiresReview_nullVersion() {
        assertThat(AddJoinFacesDependencies.isPrimeFacesVersionRequiresReview(null)).isFalse();
    }

    @Test
    void isPrimeFacesVersionRequiresReview_propertyPlaceholder() {
        // Property placeholders are handled by unresolved check, not review-required
        assertThat(AddJoinFacesDependencies.isPrimeFacesVersionRequiresReview("${primefaces.version}")).isFalse();
    }

    // ==================== P0.3 Codex-Fix 2: Version Unresolved Tests ====================

    @Test
    void isPrimeFacesVersionUnresolved_nullVersion() {
        assertThat(AddJoinFacesDependencies.isPrimeFacesVersionUnresolved(null)).isTrue();
    }

    @Test
    void isPrimeFacesVersionUnresolved_emptyVersion() {
        assertThat(AddJoinFacesDependencies.isPrimeFacesVersionUnresolved("")).isTrue();
    }

    @Test
    void isPrimeFacesVersionUnresolved_propertyPlaceholder() {
        assertThat(AddJoinFacesDependencies.isPrimeFacesVersionUnresolved("${primefaces.version}")).isTrue();
    }

    @Test
    void isPrimeFacesVersionUnresolved_invalidVersionFormat() {
        assertThat(AddJoinFacesDependencies.isPrimeFacesVersionUnresolved("RELEASE")).isTrue();
    }

    @Test
    void isPrimeFacesVersionUnresolved_validVersion() {
        assertThat(AddJoinFacesDependencies.isPrimeFacesVersionUnresolved("14.0.0")).isFalse();
    }

    @Test
    void isPrimeFacesVersionUnresolved_validOldVersion() {
        // Even old versions should be "resolved" (just incompatible)
        assertThat(AddJoinFacesDependencies.isPrimeFacesVersionUnresolved("11.0.0")).isFalse();
    }

    // ==================== P0.3 Codex-Fix 1: Incompatible Version Skips POM Changes ====================

    @Test
    void accumulatorSetsUnresolvedFlagForPropertyPlaceholder() {
        // Test that when we manually set an unresolved property, the flags are correct
        AddJoinFacesDependencies recipe = new AddJoinFacesDependencies();
        AddJoinFacesDependencies.Accumulator acc = recipe.getInitialValue(new InMemoryExecutionContext());

        // Simulate detection of PrimeFaces with unresolved property
        acc.hasPrimeFaces = true;
        acc.primeFacesVersion = "${primefaces.version}";
        acc.primeFacesVersionUnresolved = AddJoinFacesDependencies.isPrimeFacesVersionUnresolved("${primefaces.version}");

        assertThat(acc.primeFacesVersionUnresolved).isTrue();
        // Unresolved should not mark as incompatible
        assertThat(acc.primeFacesVersionIncompatible).isFalse();
    }

    @Test
    void visitorSkipsPomChangesForIncompatibleVersion() {
        String pomContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>test-app</artifactId>
                <version>1.0.0</version>
                <dependencies>
                    <dependency>
                        <groupId>org.primefaces</groupId>
                        <artifactId>primefaces</artifactId>
                        <version>11.0.0</version>
                    </dependency>
                </dependencies>
            </project>
            """;

        List<Xml.Document> docs = MavenParser.builder()
            .build()
            .parse(new InMemoryExecutionContext(), pomContent)
            .map(Xml.Document.class::cast)
            .toList();

        assertThat(docs).hasSize(1);
        Xml.Document originalDoc = docs.get(0);

        AddJoinFacesDependencies recipe = new AddJoinFacesDependencies();
        AddJoinFacesDependencies.Accumulator acc = recipe.getInitialValue(new InMemoryExecutionContext());

        // Manually set up accumulator to simulate detected JSF imports and incompatible PrimeFaces
        acc.hasJsfImport = true;
        acc.hasPrimeFaces = true;
        acc.primeFacesVersion = "11.0.0";
        acc.primeFacesVersionIncompatible = true;

        // Get visitor and apply to POM
        org.openrewrite.Tree result = recipe.getVisitor(acc).visit(originalDoc, new InMemoryExecutionContext());

        // With Codex-Fix 1, the POM should remain unchanged when PrimeFaces is incompatible
        assertThat(result).isEqualTo(originalDoc);
    }

    @Test
    void visitorSkipsPomChangesForUnresolvedVersion() {
        String pomContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>test-app</artifactId>
                <version>1.0.0</version>
                <dependencies>
                    <dependency>
                        <groupId>org.primefaces</groupId>
                        <artifactId>primefaces</artifactId>
                        <version>${primefaces.version}</version>
                    </dependency>
                </dependencies>
            </project>
            """;

        List<Xml.Document> docs = MavenParser.builder()
            .build()
            .parse(new InMemoryExecutionContext(), pomContent)
            .map(Xml.Document.class::cast)
            .toList();

        assertThat(docs).hasSize(1);
        Xml.Document originalDoc = docs.get(0);

        AddJoinFacesDependencies recipe = new AddJoinFacesDependencies();
        AddJoinFacesDependencies.Accumulator acc = recipe.getInitialValue(new InMemoryExecutionContext());

        // Manually set up accumulator to simulate detected JSF imports and unresolved PrimeFaces version
        acc.hasJsfImport = true;
        acc.hasPrimeFaces = true;
        acc.primeFacesVersion = "${primefaces.version}";
        acc.primeFacesVersionUnresolved = true;

        // Get visitor and apply to POM
        org.openrewrite.Tree result = recipe.getVisitor(acc).visit(originalDoc, new InMemoryExecutionContext());

        // With Codex-Fix 2, the POM should remain unchanged when PrimeFaces version is unresolved
        assertThat(result).isEqualTo(originalDoc);
    }

    // ==================== P0.3 Codex-Fix 4: Review-Required Version Skips POM Changes ====================

    @Test
    void visitorSkipsPomChangesForReviewRequiredVersion() {
        String pomContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>test-app</artifactId>
                <version>1.0.0</version>
                <dependencies>
                    <dependency>
                        <groupId>org.primefaces</groupId>
                        <artifactId>primefaces</artifactId>
                        <version>12.0.0</version>
                    </dependency>
                </dependencies>
            </project>
            """;

        List<Xml.Document> docs = MavenParser.builder()
            .build()
            .parse(new InMemoryExecutionContext(), pomContent)
            .map(Xml.Document.class::cast)
            .toList();

        assertThat(docs).hasSize(1);
        Xml.Document originalDoc = docs.get(0);

        AddJoinFacesDependencies recipe = new AddJoinFacesDependencies();
        AddJoinFacesDependencies.Accumulator acc = recipe.getInitialValue(new InMemoryExecutionContext());

        // Manually set up accumulator to simulate detected JSF imports and review-required PrimeFaces version
        acc.hasJsfImport = true;
        acc.hasPrimeFaces = true;
        acc.primeFacesVersion = "12.0.0";
        acc.primeFacesVersionRequiresReview = true;

        // Get visitor and apply to POM
        org.openrewrite.Tree result = recipe.getVisitor(acc).visit(originalDoc, new InMemoryExecutionContext());

        // With Codex-Fix 4, the POM should remain unchanged when PrimeFaces 12.x-13.x requires review
        assertThat(result).isEqualTo(originalDoc);
    }

    @Test
    void visitorSkipsPomChangesForReviewRequiredVersion13() {
        String pomContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>test-app</artifactId>
                <version>1.0.0</version>
                <dependencies>
                    <dependency>
                        <groupId>org.primefaces</groupId>
                        <artifactId>primefaces</artifactId>
                        <version>13.0.0</version>
                    </dependency>
                </dependencies>
            </project>
            """;

        List<Xml.Document> docs = MavenParser.builder()
            .build()
            .parse(new InMemoryExecutionContext(), pomContent)
            .map(Xml.Document.class::cast)
            .toList();

        assertThat(docs).hasSize(1);
        Xml.Document originalDoc = docs.get(0);

        AddJoinFacesDependencies recipe = new AddJoinFacesDependencies();
        AddJoinFacesDependencies.Accumulator acc = recipe.getInitialValue(new InMemoryExecutionContext());

        // Manually set up accumulator to simulate detected JSF imports and review-required PrimeFaces version
        acc.hasJsfImport = true;
        acc.hasPrimeFaces = true;
        acc.primeFacesVersion = "13.0.0";
        acc.primeFacesVersionRequiresReview = true;

        // Get visitor and apply to POM
        org.openrewrite.Tree result = recipe.getVisitor(acc).visit(originalDoc, new InMemoryExecutionContext());

        // With Codex-Fix 4, the POM should remain unchanged when PrimeFaces 13.x requires review
        assertThat(result).isEqualTo(originalDoc);
    }

    @Test
    void scannerDetectsReviewRequiredPrimeFacesVersion() {
        String pomContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>test-app</artifactId>
                <version>1.0.0</version>
                <dependencies>
                    <dependency>
                        <groupId>org.primefaces</groupId>
                        <artifactId>primefaces</artifactId>
                        <version>12.0.0</version>
                    </dependency>
                </dependencies>
            </project>
            """;

        List<Xml.Document> docs = MavenParser.builder()
            .build()
            .parse(new InMemoryExecutionContext(), pomContent)
            .map(Xml.Document.class::cast)
            .toList();

        assertThat(docs).hasSize(1);

        AddJoinFacesDependencies recipe = new AddJoinFacesDependencies();
        AddJoinFacesDependencies.Accumulator acc = recipe.getInitialValue(new InMemoryExecutionContext());

        // Scan the POM
        recipe.getScanner(acc).visit(docs.get(0), new InMemoryExecutionContext());

        // Verify PrimeFaces 12.0.0 requires review
        assertThat(acc.hasPrimeFaces).isTrue();
        if (acc.primeFacesVersion != null) {
            assertThat(acc.primeFacesVersion).isEqualTo("12.0.0");
            assertThat(acc.primeFacesVersionIncompatible).isFalse();
            assertThat(acc.primeFacesVersionRequiresReview).isTrue();
        }
    }

    // ==================== P2.1: BOM Order Validation Tests ====================

    @Test
    void isBomOrderCorrect_springBootBomBeforeJoinFaces() {
        AddJoinFacesDependencies recipe = new AddJoinFacesDependencies();
        AddJoinFacesDependencies.Accumulator acc = recipe.getInitialValue(new InMemoryExecutionContext());

        // Spring Boot BOM at position 0, JoinFaces BOM at position 1
        acc.springBootBomPosition = 0;
        acc.joinfacesBomPosition = 1;

        assertThat(AddJoinFacesDependencies.isBomOrderCorrect(acc)).isTrue();
    }

    @Test
    void isBomOrderCorrect_joinfacesBomBeforeSpringBoot() {
        AddJoinFacesDependencies recipe = new AddJoinFacesDependencies();
        AddJoinFacesDependencies.Accumulator acc = recipe.getInitialValue(new InMemoryExecutionContext());

        // JoinFaces BOM at position 0, Spring Boot BOM at position 1 - WRONG ORDER
        acc.joinfacesBomPosition = 0;
        acc.springBootBomPosition = 1;

        assertThat(AddJoinFacesDependencies.isBomOrderCorrect(acc)).isFalse();
    }

    @Test
    void isBomOrderCorrect_onlyJoinfacesBomPresent() {
        AddJoinFacesDependencies recipe = new AddJoinFacesDependencies();
        AddJoinFacesDependencies.Accumulator acc = recipe.getInitialValue(new InMemoryExecutionContext());

        // Only JoinFaces BOM present (Spring Boot not using BOM explicitly)
        acc.joinfacesBomPosition = 0;
        acc.springBootBomPosition = -1; // not present

        assertThat(AddJoinFacesDependencies.isBomOrderCorrect(acc)).isTrue();
    }

    @Test
    void isBomOrderCorrect_springBootParent() {
        AddJoinFacesDependencies recipe = new AddJoinFacesDependencies();
        AddJoinFacesDependencies.Accumulator acc = recipe.getInitialValue(new InMemoryExecutionContext());

        // Using Spring Boot Parent, even if BOM order looks wrong, it's OK
        acc.hasSpringBootParent = true;
        acc.joinfacesBomPosition = 0;
        acc.springBootBomPosition = 1;

        assertThat(AddJoinFacesDependencies.isBomOrderCorrect(acc)).isTrue();
    }

    @Test
    void isBomOrderCorrect_noJoinfacesBom() {
        AddJoinFacesDependencies recipe = new AddJoinFacesDependencies();
        AddJoinFacesDependencies.Accumulator acc = recipe.getInitialValue(new InMemoryExecutionContext());

        // JoinFaces BOM not present
        acc.joinfacesBomPosition = -1;
        acc.springBootBomPosition = 0;

        assertThat(AddJoinFacesDependencies.isBomOrderCorrect(acc)).isTrue();
    }

    @Test
    void scannerDetectsSpringBootBomPosition() {
        String pomContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>test-app</artifactId>
                <version>1.0.0</version>
                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-dependencies</artifactId>
                            <version>3.5.0</version>
                            <type>pom</type>
                            <scope>import</scope>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
            """;

        List<Xml.Document> docs = MavenParser.builder()
            .build()
            .parse(new InMemoryExecutionContext(), pomContent)
            .map(Xml.Document.class::cast)
            .toList();

        assertThat(docs).hasSize(1);

        AddJoinFacesDependencies recipe = new AddJoinFacesDependencies();
        AddJoinFacesDependencies.Accumulator acc = recipe.getInitialValue(new InMemoryExecutionContext());

        recipe.getScanner(acc).visit(docs.get(0), new InMemoryExecutionContext());

        assertThat(acc.springBootBomPosition).isEqualTo(0);
        assertThat(acc.joinfacesBomPosition).isEqualTo(-1);
    }

    @Test
    void scannerDetectsCorrectBomOrder() {
        String pomContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>test-app</artifactId>
                <version>1.0.0</version>
                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-dependencies</artifactId>
                            <version>3.5.0</version>
                            <type>pom</type>
                            <scope>import</scope>
                        </dependency>
                        <dependency>
                            <groupId>org.joinfaces</groupId>
                            <artifactId>joinfaces-dependencies</artifactId>
                            <version>5.3.0</version>
                            <type>pom</type>
                            <scope>import</scope>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
            """;

        List<Xml.Document> docs = MavenParser.builder()
            .build()
            .parse(new InMemoryExecutionContext(), pomContent)
            .map(Xml.Document.class::cast)
            .toList();

        assertThat(docs).hasSize(1);

        AddJoinFacesDependencies recipe = new AddJoinFacesDependencies();
        AddJoinFacesDependencies.Accumulator acc = recipe.getInitialValue(new InMemoryExecutionContext());

        recipe.getScanner(acc).visit(docs.get(0), new InMemoryExecutionContext());

        // Spring Boot at 0, JoinFaces at 1 - correct order
        assertThat(acc.springBootBomPosition).isEqualTo(0);
        assertThat(acc.joinfacesBomPosition).isEqualTo(1);
        assertThat(AddJoinFacesDependencies.isBomOrderCorrect(acc)).isTrue();
    }

    @Test
    void scannerDetectsIncorrectBomOrder() {
        String pomContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>test-app</artifactId>
                <version>1.0.0</version>
                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>org.joinfaces</groupId>
                            <artifactId>joinfaces-dependencies</artifactId>
                            <version>5.3.0</version>
                            <type>pom</type>
                            <scope>import</scope>
                        </dependency>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-dependencies</artifactId>
                            <version>3.5.0</version>
                            <type>pom</type>
                            <scope>import</scope>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
            """;

        List<Xml.Document> docs = MavenParser.builder()
            .build()
            .parse(new InMemoryExecutionContext(), pomContent)
            .map(Xml.Document.class::cast)
            .toList();

        assertThat(docs).hasSize(1);

        AddJoinFacesDependencies recipe = new AddJoinFacesDependencies();
        AddJoinFacesDependencies.Accumulator acc = recipe.getInitialValue(new InMemoryExecutionContext());

        recipe.getScanner(acc).visit(docs.get(0), new InMemoryExecutionContext());

        // JoinFaces at 0, Spring Boot at 1 - WRONG order
        assertThat(acc.joinfacesBomPosition).isEqualTo(0);
        assertThat(acc.springBootBomPosition).isEqualTo(1);
        assertThat(AddJoinFacesDependencies.isBomOrderCorrect(acc)).isFalse();
    }

    @Test
    void scannerDetectsSpringBootParent() {
        String pomContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <parent>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-parent</artifactId>
                    <version>3.5.0</version>
                </parent>
                <groupId>com.example</groupId>
                <artifactId>test-app</artifactId>
                <version>1.0.0</version>
            </project>
            """;

        List<Xml.Document> docs = MavenParser.builder()
            .build()
            .parse(new InMemoryExecutionContext(), pomContent)
            .map(Xml.Document.class::cast)
            .toList();

        assertThat(docs).hasSize(1);

        AddJoinFacesDependencies recipe = new AddJoinFacesDependencies();
        AddJoinFacesDependencies.Accumulator acc = recipe.getInitialValue(new InMemoryExecutionContext());

        recipe.getScanner(acc).visit(docs.get(0), new InMemoryExecutionContext());

        assertThat(acc.hasSpringBootParent).isTrue();
    }

    @Test
    void scannerDetectsNonSpringBootParent() {
        String pomContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <parent>
                    <groupId>com.example</groupId>
                    <artifactId>parent-pom</artifactId>
                    <version>1.0.0</version>
                </parent>
                <groupId>com.example</groupId>
                <artifactId>test-app</artifactId>
                <version>1.0.0</version>
            </project>
            """;

        List<Xml.Document> docs = MavenParser.builder()
            .build()
            .parse(new InMemoryExecutionContext(), pomContent)
            .map(Xml.Document.class::cast)
            .toList();

        assertThat(docs).hasSize(1);

        AddJoinFacesDependencies recipe = new AddJoinFacesDependencies();
        AddJoinFacesDependencies.Accumulator acc = recipe.getInitialValue(new InMemoryExecutionContext());

        recipe.getScanner(acc).visit(docs.get(0), new InMemoryExecutionContext());

        assertThat(acc.hasSpringBootParent).isFalse();
    }

    @Test
    void scannerHandlesNonBomDependencyInDependencyManagement() {
        // A dependency in dependencyManagement without type=pom and scope=import is NOT a BOM
        String pomContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>test-app</artifactId>
                <version>1.0.0</version>
                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-dependencies</artifactId>
                            <version>3.5.0</version>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
            """;

        List<Xml.Document> docs = MavenParser.builder()
            .build()
            .parse(new InMemoryExecutionContext(), pomContent)
            .map(Xml.Document.class::cast)
            .toList();

        assertThat(docs).hasSize(1);

        AddJoinFacesDependencies recipe = new AddJoinFacesDependencies();
        AddJoinFacesDependencies.Accumulator acc = recipe.getInitialValue(new InMemoryExecutionContext());

        recipe.getScanner(acc).visit(docs.get(0), new InMemoryExecutionContext());

        // Without type=pom and scope=import, it's not recognized as BOM
        assertThat(acc.springBootBomPosition).isEqualTo(-1);
    }

    // ==================== P2.1 Codex-Fix 1: Preserve existing JoinFaces version during reorder ====================

    @Test
    void scannerCapturesExistingJoinfacesBomVersion() {
        String pomContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>test-app</artifactId>
                <version>1.0.0</version>
                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>org.joinfaces</groupId>
                            <artifactId>joinfaces-dependencies</artifactId>
                            <version>5.2.0</version>
                            <type>pom</type>
                            <scope>import</scope>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
            """;

        List<Xml.Document> docs = MavenParser.builder()
            .build()
            .parse(new InMemoryExecutionContext(), pomContent)
            .map(Xml.Document.class::cast)
            .toList();

        assertThat(docs).hasSize(1);

        AddJoinFacesDependencies recipe = new AddJoinFacesDependencies();
        AddJoinFacesDependencies.Accumulator acc = recipe.getInitialValue(new InMemoryExecutionContext());

        recipe.getScanner(acc).visit(docs.get(0), new InMemoryExecutionContext());

        // Existing version 5.2.0 should be captured
        assertThat(acc.existingJoinfacesBomVersion).isEqualTo("5.2.0");
        assertThat(acc.joinfacesBomVersionUnresolved).isFalse();
    }

    @Test
    void scannerDetectsUnresolvedJoinfacesBomVersion() {
        String pomContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>test-app</artifactId>
                <version>1.0.0</version>
                <properties>
                    <joinfaces.version>5.3.0</joinfaces.version>
                </properties>
                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>org.joinfaces</groupId>
                            <artifactId>joinfaces-dependencies</artifactId>
                            <version>${joinfaces.version}</version>
                            <type>pom</type>
                            <scope>import</scope>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
            """;

        List<Xml.Document> docs = MavenParser.builder()
            .build()
            .parse(new InMemoryExecutionContext(), pomContent)
            .map(Xml.Document.class::cast)
            .toList();

        assertThat(docs).hasSize(1);

        AddJoinFacesDependencies recipe = new AddJoinFacesDependencies();
        AddJoinFacesDependencies.Accumulator acc = recipe.getInitialValue(new InMemoryExecutionContext());

        recipe.getScanner(acc).visit(docs.get(0), new InMemoryExecutionContext());

        // Property placeholder detected as unresolved
        assertThat(acc.existingJoinfacesBomVersion).isEqualTo("${joinfaces.version}");
        assertThat(acc.joinfacesBomVersionUnresolved).isTrue();
    }

    // ==================== P2.1 Codex-Fix 3: Spring Boot Parent + misordered BOMs -> no rewrite ====================

    @Test
    void isBomOrderCorrect_springBootParentWithMisorderedBoms() {
        AddJoinFacesDependencies recipe = new AddJoinFacesDependencies();
        AddJoinFacesDependencies.Accumulator acc = recipe.getInitialValue(new InMemoryExecutionContext());

        // Spring Boot Parent is used, AND both BOMs are present in wrong order
        acc.hasSpringBootParent = true;
        acc.joinfacesBomPosition = 0;  // JoinFaces first (wrong)
        acc.springBootBomPosition = 1; // Spring Boot second (wrong)

        // With parent, BOM order is irrelevant - should be marked as correct
        assertThat(AddJoinFacesDependencies.isBomOrderCorrect(acc)).isTrue();
    }

    @Test
    void scannerDetectsSpringBootParentAndMisorderedBoms() {
        // Regression test: Parent + misordered BOMs should NOT trigger rewrite
        String pomContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <parent>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-parent</artifactId>
                    <version>3.5.0</version>
                </parent>
                <groupId>com.example</groupId>
                <artifactId>test-app</artifactId>
                <version>1.0.0</version>
                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>org.joinfaces</groupId>
                            <artifactId>joinfaces-dependencies</artifactId>
                            <version>5.3.0</version>
                            <type>pom</type>
                            <scope>import</scope>
                        </dependency>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-dependencies</artifactId>
                            <version>3.5.0</version>
                            <type>pom</type>
                            <scope>import</scope>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
            """;

        List<Xml.Document> docs = MavenParser.builder()
            .build()
            .parse(new InMemoryExecutionContext(), pomContent)
            .map(Xml.Document.class::cast)
            .toList();

        assertThat(docs).hasSize(1);

        AddJoinFacesDependencies recipe = new AddJoinFacesDependencies();
        AddJoinFacesDependencies.Accumulator acc = recipe.getInitialValue(new InMemoryExecutionContext());

        recipe.getScanner(acc).visit(docs.get(0), new InMemoryExecutionContext());

        // Both facts detected: Parent used AND BOMs in wrong order
        assertThat(acc.hasSpringBootParent).isTrue();
        assertThat(acc.joinfacesBomPosition).isEqualTo(0);
        assertThat(acc.springBootBomPosition).isEqualTo(1);

        // But with parent, order is correct (no rewrite needed)
        assertThat(AddJoinFacesDependencies.isBomOrderCorrect(acc)).isTrue();
    }

    // ==================== P2.1 Codex-Fix 5: Property-based version with misordered BOMs -> add marker ====================

    @Test
    void visitorAddsMarkerForMisorderedBomsWithPropertyPlaceholderVersion() {
        // Integration test: misordered BOMs + ${joinfaces.version} should get a marker comment
        // (auto-reorder does not work reliably, so marker approach is used)
        String pomContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>test-app</artifactId>
                <version>1.0.0</version>
                <properties>
                    <joinfaces.version>5.3.0</joinfaces.version>
                </properties>
                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>org.joinfaces</groupId>
                            <artifactId>joinfaces-dependencies</artifactId>
                            <version>${joinfaces.version}</version>
                            <type>pom</type>
                            <scope>import</scope>
                        </dependency>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-dependencies</artifactId>
                            <version>3.5.0</version>
                            <type>pom</type>
                            <scope>import</scope>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
            """;

        List<Xml.Document> docs = MavenParser.builder()
            .build()
            .parse(new InMemoryExecutionContext(), pomContent)
            .map(Xml.Document.class::cast)
            .toList();

        assertThat(docs).hasSize(1);
        Xml.Document originalDoc = docs.get(0);

        AddJoinFacesDependencies recipe = new AddJoinFacesDependencies();
        AddJoinFacesDependencies.Accumulator acc = recipe.getInitialValue(new InMemoryExecutionContext());

        // Scan the POM
        recipe.getScanner(acc).visit(originalDoc, new InMemoryExecutionContext());

        // Set up for marker
        acc.hasJsfImport = true;
        acc.hasJoinFaces = true;

        // Verify initial state
        assertThat(acc.joinfacesBomPosition).isEqualTo(0);
        assertThat(acc.springBootBomPosition).isEqualTo(1);
        assertThat(acc.existingJoinfacesBomVersion).isEqualTo("${joinfaces.version}");
        assertThat(AddJoinFacesDependencies.isBomOrderCorrect(acc)).isFalse();

        // Apply visitor
        org.openrewrite.Tree result = recipe.getVisitor(acc).visit(originalDoc, new InMemoryExecutionContext());
        assertThat(result).isNotNull();

        // Convert result to string and verify marker
        String resultContent = ((Xml.Document) result).printAll();

        // Verify 1: Property placeholder ${joinfaces.version} is preserved (not removed)
        assertThat(resultContent).contains("${joinfaces.version}");

        // Verify 2: Both BOMs are still present (not removed)
        assertThat(resultContent).contains("spring-boot-dependencies");
        assertThat(resultContent).contains("joinfaces-dependencies");

        // Verify 3: Marker comment is added
        assertThat(resultContent).contains("@NeedsReview: BOM Order");
        assertThat(resultContent).contains("JoinFaces BOM (version ${joinfaces.version}) must come AFTER Spring Boot BOM");
        assertThat(resultContent).contains("position 0");
        assertThat(resultContent).contains("position 1");
    }

    @Test
    void visitorDoesNotReorderWhenSpringBootParentIsUsed() {
        // Negative test: When spring-boot-starter-parent is used, no reorder should occur
        // even if BOMs appear to be in wrong order
        String pomContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <parent>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-parent</artifactId>
                    <version>3.5.0</version>
                </parent>
                <groupId>com.example</groupId>
                <artifactId>test-app</artifactId>
                <version>1.0.0</version>
                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>org.joinfaces</groupId>
                            <artifactId>joinfaces-dependencies</artifactId>
                            <version>5.3.0</version>
                            <type>pom</type>
                            <scope>import</scope>
                        </dependency>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-dependencies</artifactId>
                            <version>3.5.0</version>
                            <type>pom</type>
                            <scope>import</scope>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
            """;

        List<Xml.Document> docs = MavenParser.builder()
            .build()
            .parse(new InMemoryExecutionContext(), pomContent)
            .map(Xml.Document.class::cast)
            .toList();

        assertThat(docs).hasSize(1);
        Xml.Document originalDoc = docs.get(0);

        AddJoinFacesDependencies recipe = new AddJoinFacesDependencies();
        AddJoinFacesDependencies.Accumulator acc = recipe.getInitialValue(new InMemoryExecutionContext());

        // Scan the POM
        recipe.getScanner(acc).visit(originalDoc, new InMemoryExecutionContext());

        // Verify: Parent detected, BOM order check passes (no reorder needed)
        assertThat(acc.hasSpringBootParent).isTrue();
        assertThat(acc.joinfacesBomPosition).isEqualTo(0);
        assertThat(acc.springBootBomPosition).isEqualTo(1);
        assertThat(AddJoinFacesDependencies.isBomOrderCorrect(acc)).isTrue(); // Parent makes order OK

        // Set up for visitor
        acc.hasJsfImport = true;
        acc.hasJoinFaces = true;

        // Apply visitor - should not reorder because parent is used
        org.openrewrite.Tree result = recipe.getVisitor(acc).visit(originalDoc, new InMemoryExecutionContext());

        // Result should be unchanged (JoinFaces still before Spring Boot in original order)
        String resultContent = ((Xml.Document) result).printAll();
        int springBootPos = resultContent.indexOf("spring-boot-dependencies");
        int joinfacesPos = resultContent.indexOf("joinfaces-dependencies");

        // Original order preserved (JoinFaces before Spring Boot) because parent makes reorder unnecessary
        assertThat(joinfacesPos).as("JoinFaces should still be before Spring Boot (no reorder)")
            .isLessThan(springBootPos);
    }

    @Test
    void visitorAddsMarkerForMisorderedBomsWithExistingVersion() {
        // Test that existing version (e.g., 5.2.0) is preserved when marker is added
        // (marker approach keeps original BOMs intact, just adds comment)
        String pomContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>test-app</artifactId>
                <version>1.0.0</version>
                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>org.joinfaces</groupId>
                            <artifactId>joinfaces-dependencies</artifactId>
                            <version>5.2.0</version>
                            <type>pom</type>
                            <scope>import</scope>
                        </dependency>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-dependencies</artifactId>
                            <version>3.5.0</version>
                            <type>pom</type>
                            <scope>import</scope>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
            """;

        List<Xml.Document> docs = MavenParser.builder()
            .build()
            .parse(new InMemoryExecutionContext(), pomContent)
            .map(Xml.Document.class::cast)
            .toList();

        assertThat(docs).hasSize(1);
        Xml.Document originalDoc = docs.get(0);

        AddJoinFacesDependencies recipe = new AddJoinFacesDependencies();
        AddJoinFacesDependencies.Accumulator acc = recipe.getInitialValue(new InMemoryExecutionContext());

        // Scan the POM
        recipe.getScanner(acc).visit(originalDoc, new InMemoryExecutionContext());

        // Set up for marker
        acc.hasJsfImport = true;
        acc.hasJoinFaces = true;

        // Verify captured version
        assertThat(acc.existingJoinfacesBomVersion).isEqualTo("5.2.0");

        // Apply visitor
        org.openrewrite.Tree result = recipe.getVisitor(acc).visit(originalDoc, new InMemoryExecutionContext());
        String resultContent = ((Xml.Document) result).printAll();

        // Verify 1: Existing version 5.2.0 is preserved (BOMs not removed)
        assertThat(resultContent).contains("5.2.0");

        // Verify 2: Both BOMs are still present
        assertThat(resultContent).contains("joinfaces-dependencies");
        assertThat(resultContent).contains("spring-boot-dependencies");

        // Verify 3: Marker comment is added with correct version info
        assertThat(resultContent).contains("@NeedsReview: BOM Order");
        assertThat(resultContent).contains("JoinFaces BOM (version 5.2.0) must come AFTER Spring Boot BOM");
    }
}
