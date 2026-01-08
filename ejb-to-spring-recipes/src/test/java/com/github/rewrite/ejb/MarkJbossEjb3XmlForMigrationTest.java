package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.xml.Assertions.xml;

/**
 * Tests for {@link MarkJbossEjb3XmlForMigration}.
 */
class MarkJbossEjb3XmlForMigrationTest implements RewriteTest {

    private static final String NEEDS_REVIEW_SOURCE = """
        package com.github.rewrite.ejb.annotations;

        import java.lang.annotation.*;

        @Documented
        @Repeatable(NeedsReview.Container.class)
        @Retention(RetentionPolicy.SOURCE)
        @Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER})
        public @interface NeedsReview {
            String reason();
            Category category();
            String originalCode() default "";
            String suggestedAction() default "";
            String stableKey() default "";

            enum Category {
                REMOTE_ACCESS, CONCURRENCY, CONFIGURATION, SCHEDULING, MESSAGING,
                CDI_FEATURE, TRANSACTION, ASYNC, SPRING_CONFIG, MANUAL_MIGRATION,
                SEMANTIC_CHANGE, OTHER
            }

            @Documented
            @Retention(RetentionPolicy.SOURCE)
            @Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER})
            @interface Container {
                NeedsReview[] value();
            }
        }
        """;

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MarkJbossEjb3XmlForMigration())
            .parser(JavaParser.fromJavaVersion()
                .dependsOn(NEEDS_REVIEW_SOURCE))
            .typeValidationOptions(TypeValidation.none());
    }

    @Test
    @DocumentExample
    void marksDeliveryActiveFalseOnMdb() {
        rewriteRun(
            // jboss-ejb3.xml with delivery-active=false
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <jboss:ejb-jar xmlns:jboss="http://www.jboss.com/xml/ns/javaee"
                               xmlns="http://java.sun.com/xml/ns/javaee">
                    <assembly-descriptor>
                        <mdb>
                            <ejb-name>OrderEventMDB</ejb-name>
                            <delivery-active>false</delivery-active>
                        </mdb>
                    </assembly-descriptor>
                </jboss:ejb-jar>
                """,
                spec -> spec.path("src/main/resources/META-INF/jboss-ejb3.xml")
            ),
            // MDB class that matches ejb-name
            java(
                """
                package com.example.mdb;

                import jakarta.ejb.MessageDriven;
                import jakarta.jms.Message;
                import jakarta.jms.MessageListener;

                @MessageDriven
                public class OrderEventMDB implements MessageListener {
                    @Override
                    public void onMessage(Message message) {
                        // Process order event
                    }
                }
                """,
                """
                package com.example.mdb;

                import com.github.rewrite.ejb.annotations.NeedsReview;
                import com.github.rewrite.ejb.annotations.NeedsReview.Category;
                import jakarta.ejb.MessageDriven;
                import jakarta.jms.Message;
                import jakarta.jms.MessageListener;

                @MessageDriven
                @NeedsReview(
                        reason = "JBoss delivery-active=false detected in jboss-ejb3.xml",
                        category = Category.MESSAGING,
                        originalCode = "<mdb><ejb-name>OrderEventMDB</ejb-name><delivery-active>false</delivery-active></mdb>",
                        suggestedAction = "Use @JmsListener(autoStartup = false) to disable message delivery at startup"
                )
                public class OrderEventMDB implements MessageListener {
                    @Override
                    public void onMessage(Message message) {
                        // Process order event
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/example/mdb/OrderEventMDB.java")
            )
        );
    }

    @Test
    void marksClusteredSingletonTrue() {
        rewriteRun(
            // jboss-ejb3.xml with clustered-singleton=true
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <jboss:ejb-jar xmlns:jboss="http://www.jboss.com/xml/ns/javaee"
                               xmlns="http://java.sun.com/xml/ns/javaee"
                               xmlns:s="urn:clustering:singleton:1.0">
                    <assembly-descriptor>
                        <s:singleton>
                            <ejb-name>SchedulerService</ejb-name>
                            <s:clustered-singleton>true</s:clustered-singleton>
                        </s:singleton>
                    </assembly-descriptor>
                </jboss:ejb-jar>
                """,
                spec -> spec.path("src/main/resources/META-INF/jboss-ejb3.xml")
            ),
            // Singleton class that matches ejb-name
            java(
                """
                package com.example.service;

                import jakarta.ejb.Singleton;
                import jakarta.ejb.Startup;

                @Singleton
                @Startup
                public class SchedulerService {
                    public void runScheduledTask() {
                        // Scheduled task
                    }
                }
                """,
                """
                package com.example.service;

                import com.github.rewrite.ejb.annotations.NeedsReview;
                import com.github.rewrite.ejb.annotations.NeedsReview.Category;
                import jakarta.ejb.Singleton;
                import jakarta.ejb.Startup;

                @NeedsReview(
                        reason = "JBoss clustered-singleton=true detected in jboss-ejb3.xml",
                        category = Category.CONCURRENCY,
                        originalCode = "<singleton><ejb-name>SchedulerService</ejb-name><clustered-singleton>true</clustered-singleton></singleton>",
                        suggestedAction = "Options: ShedLock (JDBC, recommended), Spring Integration JdbcLockRegistry, or Spring Cloud Leader Election"
                )
                @Singleton
                @Startup
                public class SchedulerService {
                    public void runScheduledTask() {
                        // Scheduled task
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/example/service/SchedulerService.java")
            )
        );
    }

    @Test
    void marksBothDeliveryActiveAndClusteredSingleton() {
        rewriteRun(
            // jboss-ejb3.xml with both settings
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <jboss:ejb-jar xmlns:jboss="http://www.jboss.com/xml/ns/javaee"
                               xmlns="http://java.sun.com/xml/ns/javaee"
                               xmlns:s="urn:clustering:singleton:1.0">
                    <assembly-descriptor>
                        <mdb>
                            <ejb-name>EventProcessor</ejb-name>
                            <delivery-active>false</delivery-active>
                        </mdb>
                        <s:singleton>
                            <ejb-name>ClusterCoordinator</ejb-name>
                            <s:clustered-singleton>true</s:clustered-singleton>
                        </s:singleton>
                    </assembly-descriptor>
                </jboss:ejb-jar>
                """,
                spec -> spec.path("src/main/resources/META-INF/jboss-ejb3.xml")
            ),
            // MDB class
            java(
                """
                package com.example.mdb;

                import jakarta.ejb.MessageDriven;
                import jakarta.jms.Message;
                import jakarta.jms.MessageListener;

                @MessageDriven
                public class EventProcessor implements MessageListener {
                    @Override
                    public void onMessage(Message message) {}
                }
                """,
                """
                package com.example.mdb;

                import com.github.rewrite.ejb.annotations.NeedsReview;
                import com.github.rewrite.ejb.annotations.NeedsReview.Category;
                import jakarta.ejb.MessageDriven;
                import jakarta.jms.Message;
                import jakarta.jms.MessageListener;

                @MessageDriven
                @NeedsReview(
                        reason = "JBoss delivery-active=false detected in jboss-ejb3.xml",
                        category = Category.MESSAGING,
                        originalCode = "<mdb><ejb-name>EventProcessor</ejb-name><delivery-active>false</delivery-active></mdb>",
                        suggestedAction = "Use @JmsListener(autoStartup = false) to disable message delivery at startup"
                )
                public class EventProcessor implements MessageListener {
                    @Override
                    public void onMessage(Message message) {}
                }
                """,
                spec -> spec.path("src/main/java/com/example/mdb/EventProcessor.java")
            ),
            // Singleton class
            java(
                """
                package com.example.service;

                import jakarta.ejb.Singleton;

                @Singleton
                public class ClusterCoordinator {
                    public void coordinate() {}
                }
                """,
                """
                package com.example.service;

                import com.github.rewrite.ejb.annotations.NeedsReview;
                import com.github.rewrite.ejb.annotations.NeedsReview.Category;
                import jakarta.ejb.Singleton;

                @NeedsReview(
                        reason = "JBoss clustered-singleton=true detected in jboss-ejb3.xml",
                        category = Category.CONCURRENCY,
                        originalCode = "<singleton><ejb-name>ClusterCoordinator</ejb-name><clustered-singleton>true</clustered-singleton></singleton>",
                        suggestedAction = "Options: ShedLock (JDBC, recommended), Spring Integration JdbcLockRegistry, or Spring Cloud Leader Election"
                )
                @Singleton
                public class ClusterCoordinator {
                    public void coordinate() {}
                }
                """,
                spec -> spec.path("src/main/java/com/example/service/ClusterCoordinator.java")
            )
        );
    }

    @Test
    void handlesEnterpriseBeansSection() {
        rewriteRun(
            // jboss-ejb3.xml with enterprise-beans section (alternative structure)
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <jboss:ejb-jar xmlns:jboss="http://www.jboss.com/xml/ns/javaee"
                               xmlns="http://java.sun.com/xml/ns/javaee">
                    <enterprise-beans>
                        <message-driven>
                            <ejb-name>NotificationMDB</ejb-name>
                            <delivery-active>false</delivery-active>
                        </message-driven>
                    </enterprise-beans>
                </jboss:ejb-jar>
                """,
                spec -> spec.path("src/main/resources/META-INF/jboss-ejb3.xml")
            ),
            // MDB class
            java(
                """
                package com.example.mdb;

                import jakarta.ejb.MessageDriven;
                import jakarta.jms.Message;
                import jakarta.jms.MessageListener;

                @MessageDriven
                public class NotificationMDB implements MessageListener {
                    @Override
                    public void onMessage(Message message) {}
                }
                """,
                """
                package com.example.mdb;

                import com.github.rewrite.ejb.annotations.NeedsReview;
                import com.github.rewrite.ejb.annotations.NeedsReview.Category;
                import jakarta.ejb.MessageDriven;
                import jakarta.jms.Message;
                import jakarta.jms.MessageListener;

                @MessageDriven
                @NeedsReview(
                        reason = "JBoss delivery-active=false detected in jboss-ejb3.xml",
                        category = Category.MESSAGING,
                        originalCode = "<message-driven><ejb-name>NotificationMDB</ejb-name><delivery-active>false</delivery-active></message-driven>",
                        suggestedAction = "Use @JmsListener(autoStartup = false) to disable message delivery at startup"
                )
                public class NotificationMDB implements MessageListener {
                    @Override
                    public void onMessage(Message message) {}
                }
                """,
                spec -> spec.path("src/main/java/com/example/mdb/NotificationMDB.java")
            )
        );
    }

    @Test
    void noChangeWithoutJbossEjb3Xml() {
        rewriteRun(
            // Java source file only, no jboss-ejb3.xml
            java(
                """
                package com.example;

                import jakarta.ejb.MessageDriven;
                import jakarta.jms.Message;
                import jakarta.jms.MessageListener;

                @MessageDriven
                public class RegularMDB implements MessageListener {
                    @Override
                    public void onMessage(Message message) {}
                }
                """,
                spec -> spec.path("src/main/java/com/example/RegularMDB.java")
            )
        );
    }

    @Test
    void noChangeWhenDeliveryActiveTrue() {
        rewriteRun(
            // jboss-ejb3.xml with delivery-active=true (no change needed)
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <jboss:ejb-jar xmlns:jboss="http://www.jboss.com/xml/ns/javaee"
                               xmlns="http://java.sun.com/xml/ns/javaee">
                    <assembly-descriptor>
                        <mdb>
                            <ejb-name>ActiveMDB</ejb-name>
                            <delivery-active>true</delivery-active>
                        </mdb>
                    </assembly-descriptor>
                </jboss:ejb-jar>
                """,
                spec -> spec.path("src/main/resources/META-INF/jboss-ejb3.xml")
            ),
            // MDB class - should not be annotated
            java(
                """
                package com.example;

                import jakarta.ejb.MessageDriven;
                import jakarta.jms.Message;
                import jakarta.jms.MessageListener;

                @MessageDriven
                public class ActiveMDB implements MessageListener {
                    @Override
                    public void onMessage(Message message) {}
                }
                """,
                spec -> spec.path("src/main/java/com/example/ActiveMDB.java")
            )
        );
    }

    @Test
    void noChangeWhenClusteredSingletonFalse() {
        rewriteRun(
            // jboss-ejb3.xml with clustered-singleton=false (no change needed)
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <jboss:ejb-jar xmlns:jboss="http://www.jboss.com/xml/ns/javaee"
                               xmlns="http://java.sun.com/xml/ns/javaee"
                               xmlns:s="urn:clustering:singleton:1.0">
                    <assembly-descriptor>
                        <s:singleton>
                            <ejb-name>LocalSingleton</ejb-name>
                            <s:clustered-singleton>false</s:clustered-singleton>
                        </s:singleton>
                    </assembly-descriptor>
                </jboss:ejb-jar>
                """,
                spec -> spec.path("src/main/resources/META-INF/jboss-ejb3.xml")
            ),
            // Singleton class - should not be annotated
            java(
                """
                package com.example;

                import jakarta.ejb.Singleton;

                @Singleton
                public class LocalSingleton {
                    public void doWork() {}
                }
                """,
                spec -> spec.path("src/main/java/com/example/LocalSingleton.java")
            )
        );
    }

    @Test
    void noChangeWhenEjbNameDoesNotMatch() {
        rewriteRun(
            // jboss-ejb3.xml with settings for different EJB name
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <jboss:ejb-jar xmlns:jboss="http://www.jboss.com/xml/ns/javaee"
                               xmlns="http://java.sun.com/xml/ns/javaee">
                    <assembly-descriptor>
                        <mdb>
                            <ejb-name>OtherMDB</ejb-name>
                            <delivery-active>false</delivery-active>
                        </mdb>
                    </assembly-descriptor>
                </jboss:ejb-jar>
                """,
                spec -> spec.path("src/main/resources/META-INF/jboss-ejb3.xml")
            ),
            // MDB class with different name - should not be annotated
            java(
                """
                package com.example;

                import jakarta.ejb.MessageDriven;
                import jakarta.jms.Message;
                import jakarta.jms.MessageListener;

                @MessageDriven
                public class DifferentMDB implements MessageListener {
                    @Override
                    public void onMessage(Message message) {}
                }
                """,
                spec -> spec.path("src/main/java/com/example/DifferentMDB.java")
            )
        );
    }

    @Test
    void noChangeWhenAlreadyAnnotated() {
        rewriteRun(
            // jboss-ejb3.xml with delivery-active=false
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <jboss:ejb-jar xmlns:jboss="http://www.jboss.com/xml/ns/javaee"
                               xmlns="http://java.sun.com/xml/ns/javaee">
                    <assembly-descriptor>
                        <mdb>
                            <ejb-name>AlreadyMarkedMDB</ejb-name>
                            <delivery-active>false</delivery-active>
                        </mdb>
                    </assembly-descriptor>
                </jboss:ejb-jar>
                """,
                spec -> spec.path("src/main/resources/META-INF/jboss-ejb3.xml")
            ),
            // MDB class already annotated - should not add duplicate
            java(
                """
                package com.example;

                import com.github.rewrite.ejb.annotations.NeedsReview;
                import com.github.rewrite.ejb.annotations.NeedsReview.Category;
                import jakarta.ejb.MessageDriven;
                import jakarta.jms.Message;
                import jakarta.jms.MessageListener;

                @MessageDriven
                @NeedsReview(
                        reason = "JBoss delivery-active=false detected in jboss-ejb3.xml",
                        category = Category.MESSAGING,
                        originalCode = "<mdb><ejb-name>AlreadyMarkedMDB</ejb-name><delivery-active>false</delivery-active></mdb>",
                        suggestedAction = "Use @JmsListener(autoStartup = false) to disable message delivery at startup"
                )
                public class AlreadyMarkedMDB implements MessageListener {
                    @Override
                    public void onMessage(Message message) {}
                }
                """,
                spec -> spec.path("src/main/java/com/example/AlreadyMarkedMDB.java")
            )
        );
    }

    @Test
    void handlesSimpleEjbJarRoot() {
        rewriteRun(
            // jboss-ejb3.xml with simple ejb-jar root element
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <ejb-jar xmlns="http://java.sun.com/xml/ns/javaee">
                    <assembly-descriptor>
                        <mdb>
                            <ejb-name>SimpleMDB</ejb-name>
                            <delivery-active>false</delivery-active>
                        </mdb>
                    </assembly-descriptor>
                </ejb-jar>
                """,
                spec -> spec.path("src/main/resources/META-INF/jboss-ejb3.xml")
            ),
            // MDB class
            java(
                """
                package com.example;

                import jakarta.ejb.MessageDriven;
                import jakarta.jms.Message;
                import jakarta.jms.MessageListener;

                @MessageDriven
                public class SimpleMDB implements MessageListener {
                    @Override
                    public void onMessage(Message message) {}
                }
                """,
                """
                package com.example;

                import com.github.rewrite.ejb.annotations.NeedsReview;
                import com.github.rewrite.ejb.annotations.NeedsReview.Category;
                import jakarta.ejb.MessageDriven;
                import jakarta.jms.Message;
                import jakarta.jms.MessageListener;

                @MessageDriven
                @NeedsReview(
                        reason = "JBoss delivery-active=false detected in jboss-ejb3.xml",
                        category = Category.MESSAGING,
                        originalCode = "<mdb><ejb-name>SimpleMDB</ejb-name><delivery-active>false</delivery-active></mdb>",
                        suggestedAction = "Use @JmsListener(autoStartup = false) to disable message delivery at startup"
                )
                public class SimpleMDB implements MessageListener {
                    @Override
                    public void onMessage(Message message) {}
                }
                """,
                spec -> spec.path("src/main/java/com/example/SimpleMDB.java")
            )
        );
    }

    @Test
    void matchesByExplicitAnnotationName() {
        // Tests that @MessageDriven(name="CustomEjbName") is matched against ejb-name in XML
        rewriteRun(
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <jboss:ejb-jar xmlns:jboss="http://www.jboss.com/xml/ns/javaee"
                               xmlns="http://java.sun.com/xml/ns/javaee">
                    <assembly-descriptor>
                        <mdb>
                            <ejb-name>CustomMdbName</ejb-name>
                            <delivery-active>false</delivery-active>
                        </mdb>
                    </assembly-descriptor>
                </jboss:ejb-jar>
                """,
                spec -> spec.path("src/main/resources/META-INF/jboss-ejb3.xml")
            ),
            // MDB with explicit name attribute
            java(
                """
                package com.example;

                import jakarta.ejb.MessageDriven;
                import jakarta.jms.Message;
                import jakarta.jms.MessageListener;

                @MessageDriven(name = "CustomMdbName")
                public class MyMessageProcessor implements MessageListener {
                    @Override
                    public void onMessage(Message message) {}
                }
                """,
                """
                package com.example;

                import com.github.rewrite.ejb.annotations.NeedsReview;
                import com.github.rewrite.ejb.annotations.NeedsReview.Category;
                import jakarta.ejb.MessageDriven;
                import jakarta.jms.Message;
                import jakarta.jms.MessageListener;

                @MessageDriven(name = "CustomMdbName")
                @NeedsReview(
                        reason = "JBoss delivery-active=false detected in jboss-ejb3.xml",
                        category = Category.MESSAGING,
                        originalCode = "<mdb><ejb-name>CustomMdbName</ejb-name><delivery-active>false</delivery-active></mdb>",
                        suggestedAction = "Use @JmsListener(autoStartup = false) to disable message delivery at startup"
                )
                public class MyMessageProcessor implements MessageListener {
                    @Override
                    public void onMessage(Message message) {}
                }
                """,
                spec -> spec.path("src/main/java/com/example/MyMessageProcessor.java")
            )
        );
    }

    @Test
    void matchesSingletonByExplicitAnnotationName() {
        // Tests that @Singleton(name="CustomSingletonName") is matched against ejb-name in XML
        rewriteRun(
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <jboss:ejb-jar xmlns:jboss="http://www.jboss.com/xml/ns/javaee"
                               xmlns="http://java.sun.com/xml/ns/javaee"
                               xmlns:s="urn:clustering:singleton:1.0">
                    <assembly-descriptor>
                        <s:singleton>
                            <ejb-name>ClusterScheduler</ejb-name>
                            <s:clustered-singleton>true</s:clustered-singleton>
                        </s:singleton>
                    </assembly-descriptor>
                </jboss:ejb-jar>
                """,
                spec -> spec.path("src/main/resources/META-INF/jboss-ejb3.xml")
            ),
            // Singleton with explicit name attribute
            java(
                """
                package com.example;

                import jakarta.ejb.Singleton;
                import jakarta.ejb.Startup;

                @Singleton(name = "ClusterScheduler")
                @Startup
                public class SchedulerBean {
                    public void runJob() {}
                }
                """,
                """
                package com.example;

                import com.github.rewrite.ejb.annotations.NeedsReview;
                import com.github.rewrite.ejb.annotations.NeedsReview.Category;
                import jakarta.ejb.Singleton;
                import jakarta.ejb.Startup;

                @NeedsReview(
                        reason = "JBoss clustered-singleton=true detected in jboss-ejb3.xml",
                        category = Category.CONCURRENCY,
                        originalCode = "<singleton><ejb-name>ClusterScheduler</ejb-name><clustered-singleton>true</clustered-singleton></singleton>",
                        suggestedAction = "Options: ShedLock (JDBC, recommended), Spring Integration JdbcLockRegistry, or Spring Cloud Leader Election"
                )
                @Singleton(name = "ClusterScheduler")
                @Startup
                public class SchedulerBean {
                    public void runJob() {}
                }
                """,
                spec -> spec.path("src/main/java/com/example/SchedulerBean.java")
            )
        );
    }

    @Test
    void noMatchAcrossDifferentModules() {
        // Tests that XML in module-a does NOT match a class in module-b with the same simple name
        rewriteRun(
            // jboss-ejb3.xml in module-a
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <jboss:ejb-jar xmlns:jboss="http://www.jboss.com/xml/ns/javaee"
                               xmlns="http://java.sun.com/xml/ns/javaee">
                    <assembly-descriptor>
                        <mdb>
                            <ejb-name>SharedNameMDB</ejb-name>
                            <delivery-active>false</delivery-active>
                        </mdb>
                    </assembly-descriptor>
                </jboss:ejb-jar>
                """,
                spec -> spec.path("module-a/src/main/resources/META-INF/jboss-ejb3.xml")
            ),
            // MDB class in module-a - SHOULD be annotated
            java(
                """
                package com.example.modulea;

                import jakarta.ejb.MessageDriven;
                import jakarta.jms.Message;
                import jakarta.jms.MessageListener;

                @MessageDriven
                public class SharedNameMDB implements MessageListener {
                    @Override
                    public void onMessage(Message message) {}
                }
                """,
                """
                package com.example.modulea;

                import com.github.rewrite.ejb.annotations.NeedsReview;
                import com.github.rewrite.ejb.annotations.NeedsReview.Category;
                import jakarta.ejb.MessageDriven;
                import jakarta.jms.Message;
                import jakarta.jms.MessageListener;

                @MessageDriven
                @NeedsReview(
                        reason = "JBoss delivery-active=false detected in jboss-ejb3.xml",
                        category = Category.MESSAGING,
                        originalCode = "<mdb><ejb-name>SharedNameMDB</ejb-name><delivery-active>false</delivery-active></mdb>",
                        suggestedAction = "Use @JmsListener(autoStartup = false) to disable message delivery at startup"
                )
                public class SharedNameMDB implements MessageListener {
                    @Override
                    public void onMessage(Message message) {}
                }
                """,
                spec -> spec.path("module-a/src/main/java/com/example/modulea/SharedNameMDB.java")
            ),
            // MDB class in module-b with same simple name - should NOT be annotated
            java(
                """
                package com.example.moduleb;

                import jakarta.ejb.MessageDriven;
                import jakarta.jms.Message;
                import jakarta.jms.MessageListener;

                @MessageDriven
                public class SharedNameMDB implements MessageListener {
                    @Override
                    public void onMessage(Message message) {}
                }
                """,
                spec -> spec.path("module-b/src/main/java/com/example/moduleb/SharedNameMDB.java")
            )
        );
    }

    @Test
    void matchesWithinSameModuleOnly() {
        // Tests that when both modules have jboss-ejb3.xml, each only affects classes in its own module
        rewriteRun(
            // jboss-ejb3.xml in module-a
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <jboss:ejb-jar xmlns:jboss="http://www.jboss.com/xml/ns/javaee"
                               xmlns="http://java.sun.com/xml/ns/javaee">
                    <assembly-descriptor>
                        <mdb>
                            <ejb-name>ModuleAMDB</ejb-name>
                            <delivery-active>false</delivery-active>
                        </mdb>
                    </assembly-descriptor>
                </jboss:ejb-jar>
                """,
                spec -> spec.path("module-a/src/main/resources/META-INF/jboss-ejb3.xml")
            ),
            // jboss-ejb3.xml in module-b with different EJB
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <jboss:ejb-jar xmlns:jboss="http://www.jboss.com/xml/ns/javaee"
                               xmlns="http://java.sun.com/xml/ns/javaee">
                    <assembly-descriptor>
                        <mdb>
                            <ejb-name>ModuleBMDB</ejb-name>
                            <delivery-active>false</delivery-active>
                        </mdb>
                    </assembly-descriptor>
                </jboss:ejb-jar>
                """,
                spec -> spec.path("module-b/src/main/resources/META-INF/jboss-ejb3.xml")
            ),
            // MDB in module-a should match module-a XML
            java(
                """
                package com.example.modulea;

                import jakarta.ejb.MessageDriven;
                import jakarta.jms.Message;
                import jakarta.jms.MessageListener;

                @MessageDriven
                public class ModuleAMDB implements MessageListener {
                    @Override
                    public void onMessage(Message message) {}
                }
                """,
                """
                package com.example.modulea;

                import com.github.rewrite.ejb.annotations.NeedsReview;
                import com.github.rewrite.ejb.annotations.NeedsReview.Category;
                import jakarta.ejb.MessageDriven;
                import jakarta.jms.Message;
                import jakarta.jms.MessageListener;

                @MessageDriven
                @NeedsReview(
                        reason = "JBoss delivery-active=false detected in jboss-ejb3.xml",
                        category = Category.MESSAGING,
                        originalCode = "<mdb><ejb-name>ModuleAMDB</ejb-name><delivery-active>false</delivery-active></mdb>",
                        suggestedAction = "Use @JmsListener(autoStartup = false) to disable message delivery at startup"
                )
                public class ModuleAMDB implements MessageListener {
                    @Override
                    public void onMessage(Message message) {}
                }
                """,
                spec -> spec.path("module-a/src/main/java/com/example/modulea/ModuleAMDB.java")
            ),
            // MDB in module-b should match module-b XML
            java(
                """
                package com.example.moduleb;

                import jakarta.ejb.MessageDriven;
                import jakarta.jms.Message;
                import jakarta.jms.MessageListener;

                @MessageDriven
                public class ModuleBMDB implements MessageListener {
                    @Override
                    public void onMessage(Message message) {}
                }
                """,
                """
                package com.example.moduleb;

                import com.github.rewrite.ejb.annotations.NeedsReview;
                import com.github.rewrite.ejb.annotations.NeedsReview.Category;
                import jakarta.ejb.MessageDriven;
                import jakarta.jms.Message;
                import jakarta.jms.MessageListener;

                @MessageDriven
                @NeedsReview(
                        reason = "JBoss delivery-active=false detected in jboss-ejb3.xml",
                        category = Category.MESSAGING,
                        originalCode = "<mdb><ejb-name>ModuleBMDB</ejb-name><delivery-active>false</delivery-active></mdb>",
                        suggestedAction = "Use @JmsListener(autoStartup = false) to disable message delivery at startup"
                )
                public class ModuleBMDB implements MessageListener {
                    @Override
                    public void onMessage(Message message) {}
                }
                """,
                spec -> spec.path("module-b/src/main/java/com/example/moduleb/ModuleBMDB.java")
            )
        );
    }

    @Test
    void noChangeForNonMdbClassWithMatchingName() {
        // Tests that a non-MDB class (no @MessageDriven) with a matching name does NOT get @NeedsReview
        rewriteRun(
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <jboss:ejb-jar xmlns:jboss="http://www.jboss.com/xml/ns/javaee"
                               xmlns="http://java.sun.com/xml/ns/javaee">
                    <assembly-descriptor>
                        <mdb>
                            <ejb-name>OrderEventMDB</ejb-name>
                            <delivery-active>false</delivery-active>
                        </mdb>
                    </assembly-descriptor>
                </jboss:ejb-jar>
                """,
                spec -> spec.path("src/main/resources/META-INF/jboss-ejb3.xml")
            ),
            // Regular class (NOT an MDB) with same name - should NOT be annotated
            java(
                """
                package com.example.util;

                // This is just a regular utility class, not an MDB
                public class OrderEventMDB {
                    public void process() {
                        // Not a message listener
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/example/util/OrderEventMDB.java")
            )
        );
    }

    @Test
    void noChangeForNonSingletonClassWithMatchingName() {
        // Tests that a non-Singleton class (no @Singleton) with a matching name does NOT get @NeedsReview
        rewriteRun(
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <jboss:ejb-jar xmlns:jboss="http://www.jboss.com/xml/ns/javaee"
                               xmlns="http://java.sun.com/xml/ns/javaee"
                               xmlns:s="urn:clustering:singleton:1.0">
                    <assembly-descriptor>
                        <s:singleton>
                            <ejb-name>SchedulerService</ejb-name>
                            <s:clustered-singleton>true</s:clustered-singleton>
                        </s:singleton>
                    </assembly-descriptor>
                </jboss:ejb-jar>
                """,
                spec -> spec.path("src/main/resources/META-INF/jboss-ejb3.xml")
            ),
            // Regular class (NOT a Singleton EJB) with same name - should NOT be annotated
            java(
                """
                package com.example.util;

                // This is just a regular class, not an EJB Singleton
                public class SchedulerService {
                    public void runTask() {
                        // Regular method
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/example/util/SchedulerService.java")
            )
        );
    }

    @Test
    void noChangeForStatelessBeanWithMdbName() {
        // Tests that a @Stateless bean with a name matching MDB config does NOT get @NeedsReview
        rewriteRun(
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <jboss:ejb-jar xmlns:jboss="http://www.jboss.com/xml/ns/javaee"
                               xmlns="http://java.sun.com/xml/ns/javaee">
                    <assembly-descriptor>
                        <mdb>
                            <ejb-name>SharedBeanName</ejb-name>
                            <delivery-active>false</delivery-active>
                        </mdb>
                    </assembly-descriptor>
                </jboss:ejb-jar>
                """,
                spec -> spec.path("src/main/resources/META-INF/jboss-ejb3.xml")
            ),
            // Stateless EJB with same name - should NOT be annotated (delivery-active is MDB-specific)
            java(
                """
                package com.example.service;

                import jakarta.ejb.Stateless;

                @Stateless
                public class SharedBeanName {
                    public void doWork() {
                        // Stateless bean, not an MDB
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/example/service/SharedBeanName.java")
            )
        );
    }

    @Test
    void noChangeForStatefulBeanWithSingletonClusterName() {
        // Tests that a @Stateful bean with a name matching Singleton cluster config does NOT get @NeedsReview
        rewriteRun(
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <jboss:ejb-jar xmlns:jboss="http://www.jboss.com/xml/ns/javaee"
                               xmlns="http://java.sun.com/xml/ns/javaee"
                               xmlns:s="urn:clustering:singleton:1.0">
                    <assembly-descriptor>
                        <s:singleton>
                            <ejb-name>AnotherSharedName</ejb-name>
                            <s:clustered-singleton>true</s:clustered-singleton>
                        </s:singleton>
                    </assembly-descriptor>
                </jboss:ejb-jar>
                """,
                spec -> spec.path("src/main/resources/META-INF/jboss-ejb3.xml")
            ),
            // Stateful EJB with same name - should NOT be annotated (clustered-singleton is Singleton-specific)
            java(
                """
                package com.example.service;

                import jakarta.ejb.Stateful;

                @Stateful
                public class AnotherSharedName {
                    public void doWork() {
                        // Stateful bean, not a Singleton
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/example/service/AnotherSharedName.java")
            )
        );
    }
}
