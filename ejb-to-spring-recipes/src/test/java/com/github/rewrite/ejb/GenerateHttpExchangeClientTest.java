package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;

/**
 * Tests for GenerateHttpExchangeClient recipe.
 * <p>
 * Covers:
 * <ul>
 *   <li>Client interface generation with @HttpExchange</li>
 *   <li>Config class generation with RestClient setup</li>
 *   <li>DTO generation for multi-parameter methods</li>
 *   <li>Custom package suffix</li>
 * </ul>
 */
class GenerateHttpExchangeClientTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.parser(JavaParser.fromJavaVersion()
                .classpath("jakarta.jakartaee-api", "spring-context", "spring-web"))
            .typeValidationOptions(TypeValidation.none());
    }

    @DocumentExample
    @Test
    void generatesClientInterfaceAndConfig() {
        rewriteRun(
            spec -> spec.recipe(new GenerateHttpExchangeClient(null)),
            // Remote interface
            java(
                """
                package com.example;

                import jakarta.ejb.Remote;

                @Remote
                public interface CustomerService {
                    Customer find(Long id);
                    void update(Customer customer);
                }
                """,
                spec -> spec.path("src/main/java/com/example/CustomerService.java")
            ),
            // Customer class for type resolution
            java(
                """
                package com.example;

                public class Customer {
                    private Long id;
                    private String name;
                }
                """,
                spec -> spec.path("src/main/java/com/example/Customer.java")
            ),
            // Generated DTO for find (Long is primitive-like)
            java(
                null,
                """
                package com.example.client;

                public class FindRequest {

                    private Long id;

                    public FindRequest() {
                    }

                    public FindRequest(Long id) {
                        this.id = id;
                    }

                    public Long getId() {
                        return id;
                    }

                    public void setId(Long id) {
                        this.id = id;
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/example/client/FindRequest.java")
            ),
            // Generated client interface - now with proper imports for non-java.lang types
            java(
                null,
                """
                package com.example.client;

                import com.example.Customer;
                import org.springframework.web.bind.annotation.RequestBody;
                import org.springframework.web.service.annotation.HttpExchange;
                import org.springframework.web.service.annotation.PostExchange;

                @HttpExchange("/api/CustomerService")
                public interface CustomerServiceClient {

                    @PostExchange("/find")
                    Customer find(@RequestBody FindRequest request);

                    @PostExchange("/update")
                    void update(@RequestBody Customer customer);
                }
                """,
                spec -> spec.path("src/main/java/com/example/client/CustomerServiceClient.java")
            ),
            // Generated config class
            java(
                null,
                """
                package com.example.client;

                import org.springframework.beans.factory.annotation.Value;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.web.client.RestClient;
                import org.springframework.web.client.support.RestClientAdapter;
                import org.springframework.web.service.invoker.HttpServiceProxyFactory;

                @Configuration
                public class CustomerServiceClientConfig {

                    @Value("${customerService.baseUrl}")
                    private String baseUrl;

                    @Bean
                    public CustomerServiceClient customerServiceClient() {
                        RestClient restClient = RestClient.builder()
                                .baseUrl(baseUrl)
                                .build();
                        HttpServiceProxyFactory factory = HttpServiceProxyFactory
                                .builderFor(RestClientAdapter.create(restClient))
                                .build();
                        return factory.createClient(CustomerServiceClient.class);
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/example/client/CustomerServiceClientConfig.java")
            )
        );
    }

    @Test
    void generatesDtoForMultipleParameters() {
        rewriteRun(
            spec -> spec.recipe(new GenerateHttpExchangeClient(null)),
            java(
                """
                package com.example;

                import jakarta.ejb.Remote;

                @Remote
                public interface BookingService {
                    void createBooking(String userId, String eventId, int seats);
                }
                """,
                spec -> spec.path("src/main/java/com/example/BookingService.java")
            ),
            // Generated DTO
            java(
                null,
                """
                package com.example.client;

                public class CreateBookingRequest {

                    private String userId;
                    private String eventId;
                    private int seats;

                    public CreateBookingRequest() {
                    }

                    public CreateBookingRequest(String userId, String eventId, int seats) {
                        this.userId = userId;
                        this.eventId = eventId;
                        this.seats = seats;
                    }

                    public String getUserId() {
                        return userId;
                    }

                    public void setUserId(String userId) {
                        this.userId = userId;
                    }

                    public String getEventId() {
                        return eventId;
                    }

                    public void setEventId(String eventId) {
                        this.eventId = eventId;
                    }

                    public int getSeats() {
                        return seats;
                    }

                    public void setSeats(int seats) {
                        this.seats = seats;
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/example/client/CreateBookingRequest.java")
            ),
            // Generated client
            java(
                null,
                """
                package com.example.client;

                import org.springframework.web.bind.annotation.RequestBody;
                import org.springframework.web.service.annotation.HttpExchange;
                import org.springframework.web.service.annotation.PostExchange;

                @HttpExchange("/api/BookingService")
                public interface BookingServiceClient {

                    @PostExchange("/createBooking")
                    void createBooking(@RequestBody CreateBookingRequest request);
                }
                """,
                spec -> spec.path("src/main/java/com/example/client/BookingServiceClient.java")
            ),
            // Generated config
            java(
                null,
                """
                package com.example.client;

                import org.springframework.beans.factory.annotation.Value;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.web.client.RestClient;
                import org.springframework.web.client.support.RestClientAdapter;
                import org.springframework.web.service.invoker.HttpServiceProxyFactory;

                @Configuration
                public class BookingServiceClientConfig {

                    @Value("${bookingService.baseUrl}")
                    private String baseUrl;

                    @Bean
                    public BookingServiceClient bookingServiceClient() {
                        RestClient restClient = RestClient.builder()
                                .baseUrl(baseUrl)
                                .build();
                        HttpServiceProxyFactory factory = HttpServiceProxyFactory
                                .builderFor(RestClientAdapter.create(restClient))
                                .build();
                        return factory.createClient(BookingServiceClient.class);
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/example/client/BookingServiceClientConfig.java")
            )
        );
    }

    @Test
    void noParameterMethodsHaveNoRequestBody() {
        rewriteRun(
            spec -> spec.recipe(new GenerateHttpExchangeClient(null)),
            java(
                """
                package com.example;

                import jakarta.ejb.Remote;

                @Remote
                public interface StatusService {
                    String getStatus();
                }
                """,
                spec -> spec.path("src/main/java/com/example/StatusService.java")
            ),
            // Generated client - no @RequestBody import for no-param method
            java(
                null,
                """
                package com.example.client;

                import org.springframework.web.service.annotation.HttpExchange;
                import org.springframework.web.service.annotation.PostExchange;

                @HttpExchange("/api/StatusService")
                public interface StatusServiceClient {

                    @PostExchange("/getStatus")
                    String getStatus();
                }
                """,
                spec -> spec.path("src/main/java/com/example/client/StatusServiceClient.java")
            ),
            // Generated config
            java(
                null,
                """
                package com.example.client;

                import org.springframework.beans.factory.annotation.Value;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.web.client.RestClient;
                import org.springframework.web.client.support.RestClientAdapter;
                import org.springframework.web.service.invoker.HttpServiceProxyFactory;

                @Configuration
                public class StatusServiceClientConfig {

                    @Value("${statusService.baseUrl}")
                    private String baseUrl;

                    @Bean
                    public StatusServiceClient statusServiceClient() {
                        RestClient restClient = RestClient.builder()
                                .baseUrl(baseUrl)
                                .build();
                        HttpServiceProxyFactory factory = HttpServiceProxyFactory
                                .builderFor(RestClientAdapter.create(restClient))
                                .build();
                        return factory.createClient(StatusServiceClient.class);
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/example/client/StatusServiceClientConfig.java")
            )
        );
    }

    @Test
    void customTargetPackageSuffix() {
        rewriteRun(
            spec -> spec.recipe(new GenerateHttpExchangeClient("api.remote")),
            java(
                """
                package com.example;

                import jakarta.ejb.Remote;

                @Remote
                public interface DataService {
                    void refresh();
                }
                """,
                spec -> spec.path("src/main/java/com/example/DataService.java")
            ),
            // No @RequestBody import when no method needs it
            java(
                null,
                """
                package com.example.api.remote;

                import org.springframework.web.service.annotation.HttpExchange;
                import org.springframework.web.service.annotation.PostExchange;

                @HttpExchange("/api/DataService")
                public interface DataServiceClient {

                    @PostExchange("/refresh")
                    void refresh();
                }
                """,
                spec -> spec.path("src/main/java/com/example/api/remote/DataServiceClient.java")
            ),
            java(
                null,
                """
                package com.example.api.remote;

                import org.springframework.beans.factory.annotation.Value;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.web.client.RestClient;
                import org.springframework.web.client.support.RestClientAdapter;
                import org.springframework.web.service.invoker.HttpServiceProxyFactory;

                @Configuration
                public class DataServiceClientConfig {

                    @Value("${dataService.baseUrl}")
                    private String baseUrl;

                    @Bean
                    public DataServiceClient dataServiceClient() {
                        RestClient restClient = RestClient.builder()
                                .baseUrl(baseUrl)
                                .build();
                        HttpServiceProxyFactory factory = HttpServiceProxyFactory
                                .builderFor(RestClientAdapter.create(restClient))
                                .build();
                        return factory.createClient(DataServiceClient.class);
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/example/api/remote/DataServiceClientConfig.java")
            )
        );
    }

    @Test
    void noChangeForNonRemoteInterface() {
        rewriteRun(
            spec -> spec.recipe(new GenerateHttpExchangeClient(null)),
            java(
                """
                package com.example;

                public interface RegularService {
                    void doSomething();
                }
                """
                // No changes - not a @Remote interface
            )
        );
    }

    @Test
    void handlesOverloadedMethodsWithUniquePathsAndDtoNames() {
        rewriteRun(
            spec -> spec.recipe(new GenerateHttpExchangeClient(null)),
            java(
                """
                package com.example;

                import jakarta.ejb.Remote;

                @Remote
                public interface SearchService {
                    String search(String query);
                    String search(String query, int maxResults);
                    String search(String query, int maxResults, boolean caseSensitive);
                }
                """,
                spec -> spec.path("src/main/java/com/example/SearchService.java")
            ),
            // First overload DTO: Search1Request
            java(
                null,
                """
                package com.example.client;

                public class Search1Request {

                    private String query;

                    public Search1Request() {
                    }

                    public Search1Request(String query) {
                        this.query = query;
                    }

                    public String getQuery() {
                        return query;
                    }

                    public void setQuery(String query) {
                        this.query = query;
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/example/client/Search1Request.java")
            ),
            // Second overload DTO: Search2Request
            java(
                null,
                """
                package com.example.client;

                public class Search2Request {

                    private String query;
                    private int maxResults;

                    public Search2Request() {
                    }

                    public Search2Request(String query, int maxResults) {
                        this.query = query;
                        this.maxResults = maxResults;
                    }

                    public String getQuery() {
                        return query;
                    }

                    public void setQuery(String query) {
                        this.query = query;
                    }

                    public int getMaxResults() {
                        return maxResults;
                    }

                    public void setMaxResults(int maxResults) {
                        this.maxResults = maxResults;
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/example/client/Search2Request.java")
            ),
            // Third overload DTO: Search3Request
            java(
                null,
                """
                package com.example.client;

                public class Search3Request {

                    private String query;
                    private int maxResults;
                    private boolean caseSensitive;

                    public Search3Request() {
                    }

                    public Search3Request(String query, int maxResults, boolean caseSensitive) {
                        this.query = query;
                        this.maxResults = maxResults;
                        this.caseSensitive = caseSensitive;
                    }

                    public String getQuery() {
                        return query;
                    }

                    public void setQuery(String query) {
                        this.query = query;
                    }

                    public int getMaxResults() {
                        return maxResults;
                    }

                    public void setMaxResults(int maxResults) {
                        this.maxResults = maxResults;
                    }

                    public boolean getCaseSensitive() {
                        return caseSensitive;
                    }

                    public void setCaseSensitive(boolean caseSensitive) {
                        this.caseSensitive = caseSensitive;
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/example/client/Search3Request.java")
            ),
            // Generated client interface with unique paths (/search/1, /search/2, /search/3)
            java(
                null,
                """
                package com.example.client;

                import org.springframework.web.bind.annotation.RequestBody;
                import org.springframework.web.service.annotation.HttpExchange;
                import org.springframework.web.service.annotation.PostExchange;

                @HttpExchange("/api/SearchService")
                public interface SearchServiceClient {

                    @PostExchange("/search/1")
                    String search(@RequestBody Search1Request request);

                    @PostExchange("/search/2")
                    String search(@RequestBody Search2Request request);

                    @PostExchange("/search/3")
                    String search(@RequestBody Search3Request request);
                }
                """,
                spec -> spec.path("src/main/java/com/example/client/SearchServiceClient.java")
            ),
            // Generated config
            java(
                null,
                """
                package com.example.client;

                import org.springframework.beans.factory.annotation.Value;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.web.client.RestClient;
                import org.springframework.web.client.support.RestClientAdapter;
                import org.springframework.web.service.invoker.HttpServiceProxyFactory;

                @Configuration
                public class SearchServiceClientConfig {

                    @Value("${searchService.baseUrl}")
                    private String baseUrl;

                    @Bean
                    public SearchServiceClient searchServiceClient() {
                        RestClient restClient = RestClient.builder()
                                .baseUrl(baseUrl)
                                .build();
                        HttpServiceProxyFactory factory = HttpServiceProxyFactory
                                .builderFor(RestClientAdapter.create(restClient))
                                .build();
                        return factory.createClient(SearchServiceClient.class);
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/example/client/SearchServiceClientConfig.java")
            )
        );
    }
}
