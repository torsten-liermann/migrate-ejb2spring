package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

/**
 * Tests for {@link MigrateInitialContextLookup}.
 */
class MigrateInitialContextLookupTest implements RewriteTest {

    // Stubs for jakarta.naming types
    private static final String NAMING_EXCEPTION_STUB = """
        package jakarta.naming;
        public class NamingException extends Exception {
            public NamingException() {}
            public NamingException(String msg) { super(msg); }
        }
        """;

    private static final String CONTEXT_STUB = """
        package jakarta.naming;
        public interface Context {
            Object lookup(String name) throws NamingException;
            void close() throws NamingException;
        }
        """;

    private static final String INITIAL_CONTEXT_STUB = """
        package jakarta.naming;
        public class InitialContext implements Context {
            public InitialContext() throws NamingException {}
            public Object lookup(String name) throws NamingException { return null; }
            public void close() throws NamingException {}
        }
        """;

    // Stubs for javax.naming types
    private static final String JAVAX_NAMING_EXCEPTION_STUB = """
        package javax.naming;
        public class NamingException extends Exception {
            public NamingException() {}
            public NamingException(String msg) { super(msg); }
        }
        """;

    private static final String JAVAX_CONTEXT_STUB = """
        package javax.naming;
        public interface Context {
            Object lookup(String name) throws NamingException;
            void close() throws NamingException;
        }
        """;

    private static final String JAVAX_INITIAL_CONTEXT_STUB = """
        package javax.naming;
        public class InitialContext implements Context {
            public InitialContext() throws NamingException {}
            public Object lookup(String name) throws NamingException { return null; }
            public void close() throws NamingException {}
        }
        """;

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateInitialContextLookup())
            .parser(JavaParser.fromJavaVersion()
                .dependsOn(NAMING_EXCEPTION_STUB, CONTEXT_STUB, INITIAL_CONTEXT_STUB));
    }

    @Test
    @DocumentExample
    void transformsSimpleLookup() {
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.naming.InitialContext;
                import jakarta.naming.NamingException;

                public class JndiService {

                    public Object getResource() throws NamingException {
                        return new InitialContext().lookup("java:comp/env/myResource");
                    }
                }
                """,
                """
                package com.example;

                import jakarta.naming.InitialContext;
                import jakarta.naming.NamingException;

                public class JndiService {

                    public Object getResource() throws NamingException {
                        return /* @NeedsReview: JNDI lookup 'java:comp/env/myResource' → @Value("${myResource}") */ new InitialContext().lookup("java:comp/env/myResource");
                    }
                }
                """
            )
        );
    }

    @Test
    void transformsMailSessionLookup() {
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.naming.InitialContext;
                import jakarta.naming.NamingException;

                public class MailService {

                    public Object getMailSession() throws NamingException {
                        return new InitialContext().lookup("java:jboss/mail/OesioMail");
                    }
                }
                """,
                """
                package com.example;

                import jakarta.naming.InitialContext;
                import jakarta.naming.NamingException;

                public class MailService {

                    public Object getMailSession() throws NamingException {
                        return /* @NeedsReview: JNDI lookup 'java:jboss/mail/OesioMail' → spring.mail.* properties + JavaMailSender bean */ new InitialContext().lookup("java:jboss/mail/OesioMail");
                    }
                }
                """
            )
        );
    }

    @Test
    void transformsDataSourceLookup() {
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.naming.InitialContext;
                import jakarta.naming.NamingException;

                public class DbService {

                    public Object getDataSource() throws NamingException {
                        return new InitialContext().lookup("java:/jdbc/MyDataSource");
                    }
                }
                """,
                """
                package com.example;

                import jakarta.naming.InitialContext;
                import jakarta.naming.NamingException;

                public class DbService {

                    public Object getDataSource() throws NamingException {
                        return /* @NeedsReview: JNDI lookup 'java:/jdbc/MyDataSource' → @Autowired DataSource or spring.datasource.* properties */ new InitialContext().lookup("java:/jdbc/MyDataSource");
                    }
                }
                """
            )
        );
    }

    @Test
    void transformsEjbLookup() {
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.naming.InitialContext;
                import jakarta.naming.NamingException;

                public class EjbClient {

                    public Object getEjb() throws NamingException {
                        return new InitialContext().lookup("java:global/myapp/MyService");
                    }
                }
                """,
                """
                package com.example;

                import jakarta.naming.InitialContext;
                import jakarta.naming.NamingException;

                public class EjbClient {

                    public Object getEjb() throws NamingException {
                        return /* @NeedsReview: JNDI lookup 'java:global/myapp/MyService' → @Autowired + @Qualifier or ApplicationContext.getBean() */ new InitialContext().lookup("java:global/myapp/MyService");
                    }
                }
                """
            )
        );
    }

    @Test
    void transformsDynamicLookup() {
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.naming.InitialContext;
                import jakarta.naming.NamingException;

                public class DynamicService {

                    public Object getResource(String jndiName) throws NamingException {
                        return new InitialContext().lookup(jndiName);
                    }
                }
                """,
                """
                package com.example;

                import jakarta.naming.InitialContext;
                import jakarta.naming.NamingException;

                public class DynamicService {

                    public Object getResource(String jndiName) throws NamingException {
                        return /* @NeedsReview: Dynamic JNDI lookup → ApplicationContext.getBean() or @Autowired */ new InitialContext().lookup(jndiName);
                    }
                }
                """
            )
        );
    }

    @Test
    void transformsContextVariable() {
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.naming.Context;
                import jakarta.naming.InitialContext;
                import jakarta.naming.NamingException;

                public class ContextService {

                    public Object getResource() throws NamingException {
                        Context ctx = new InitialContext();
                        return ctx.lookup("java:comp/env/config");
                    }
                }
                """,
                """
                package com.example;

                import jakarta.naming.Context;
                import jakarta.naming.InitialContext;
                import jakarta.naming.NamingException;

                public class ContextService {

                    public Object getResource() throws NamingException {
                        Context ctx = new InitialContext();
                        return /* @NeedsReview: JNDI lookup 'java:comp/env/config' → @Value("${config}") */ ctx.lookup("java:comp/env/config");
                    }
                }
                """
            )
        );
    }

    @Test
    void transformsJmsLookup() {
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.naming.InitialContext;
                import jakarta.naming.NamingException;

                public class JmsService {

                    public Object getConnectionFactory() throws NamingException {
                        return new InitialContext().lookup("java:/jms/ConnectionFactory");
                    }
                }
                """,
                """
                package com.example;

                import jakarta.naming.InitialContext;
                import jakarta.naming.NamingException;

                public class JmsService {

                    public Object getConnectionFactory() throws NamingException {
                        return /* @NeedsReview: JNDI lookup 'java:/jms/ConnectionFactory' → @Autowired JmsTemplate or spring.jms.* properties */ new InitialContext().lookup("java:/jms/ConnectionFactory");
                    }
                }
                """
            )
        );
    }

    @Test
    void noChangeWithoutJndiLookup() {
        rewriteRun(
            java(
                """
                package com.example;

                public class RegularService {

                    public void doSomething() {
                        System.out.println("No JNDI here");
                    }
                }
                """
            )
        );
    }

    @Test
    void transformsJavaxNamespace() {
        rewriteRun(
            spec -> spec.parser(JavaParser.fromJavaVersion()
                .dependsOn(JAVAX_NAMING_EXCEPTION_STUB, JAVAX_CONTEXT_STUB, JAVAX_INITIAL_CONTEXT_STUB)),
            java(
                """
                package com.example;

                import javax.naming.InitialContext;
                import javax.naming.NamingException;

                public class LegacyService {

                    public Object getResource() throws NamingException {
                        return new InitialContext().lookup("java:comp/env/legacy");
                    }
                }
                """,
                """
                package com.example;

                import javax.naming.InitialContext;
                import javax.naming.NamingException;

                public class LegacyService {

                    public Object getResource() throws NamingException {
                        return /* @NeedsReview: JNDI lookup 'java:comp/env/legacy' → @Value("${legacy}") */ new InitialContext().lookup("java:comp/env/legacy");
                    }
                }
                """
            )
        );
    }

    @Test
    void noFalsePositiveOnNonJndiLookup() {
        // Test that name-based heuristics don't cause false positives
        rewriteRun(
            java(
                """
                package com.example;

                import java.util.Map;

                public class MapService {

                    public Object lookup(Map<String, Object> ctx, String key) {
                        return ctx.get(key);
                    }
                }
                """
            )
        );
    }
}
