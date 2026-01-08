package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class MigrateResourceLookupToAutowiredTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateResourceLookupToAutowired())
            .parser(JavaParser.fromJavaVersion()
                .classpath("spring-context", "jakarta.jakartaee-api"));
    }

    @Test
    void usesJndiLookupForDataSource() {
        rewriteRun(
            java(
                """
                import jakarta.annotation.Resource;
                import javax.sql.DataSource;

                public class Repo {
                    @Resource(lookup = "java:comp/env/jdbc/MyDS")
                    private DataSource dataSource;
                }
                """,
                """
                import org.springframework.jndi.JndiLookup;

                import javax.sql.DataSource;

                public class Repo {
                    @JndiLookup("java:comp/env/jdbc/MyDS")
                    private DataSource dataSource;
                }
                """
            )
        );
    }

    @Test
    void usesJndiLookupForConnectionFactory() {
        rewriteRun(
            java(
                """
                import jakarta.annotation.Resource;
                import jakarta.jms.ConnectionFactory;

                public class Sender {
                    @Resource(lookup = "java:/JmsXA")
                    private ConnectionFactory connectionFactory;
                }
                """,
                """
                import jakarta.jms.ConnectionFactory;
                import org.springframework.jndi.JndiLookup;

                public class Sender {
                    @JndiLookup("java:/JmsXA")
                    private ConnectionFactory connectionFactory;
                }
                """
            )
        );
    }

    @Test
    void keepsNeedsReviewForOtherTypes() {
        rewriteRun(
            java(
                """
                package com.github.rewrite.ejb.annotations;
                import java.lang.annotation.*;
                @Documented
                @Retention(RetentionPolicy.SOURCE)
                @Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER})
                public @interface NeedsReview {
                    String reason();
                    Category category();
                    String originalCode() default "";
                    String suggestedAction() default "";
                    enum Category { CONFIGURATION }
                }
                """
            ),
            java(
                """
                import jakarta.annotation.Resource;

                public class Service {
                    @Resource(lookup = "java:comp/env/SomeValue")
                    private String value;
                }
                """,
                """
                import com.github.rewrite.ejb.annotations.NeedsReview;
                import org.springframework.beans.factory.annotation.Autowired;

                public class Service {
                    @NeedsReview(reason = "JNDI lookup 'java:comp/env/SomeValue' needs Spring configuration", category = NeedsReview.Category.CONFIGURATION, originalCode = "@Resource(lookup='java:comp/env/SomeValue')", suggestedAction = "Create @Bean definition or add to application.properties")
                    @Autowired
                    private String value;
                }
                """
            )
        );
    }
}
