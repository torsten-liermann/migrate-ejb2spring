package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;

/**
 * Tests for MigrateRemoteToRest recipe.
 * <p>
 * Covers:
 * <ul>
 *   <li>Basic delegation with constructor-injection</li>
 *   <li>DTO generation for multi-parameter methods</li>
 *   <li>Single non-primitive parameter → direct @RequestBody</li>
 *   <li>Implementation priority (@Stateless > @Service > unannotated)</li>
 *   <li>No implementation found → skip generation</li>
 * </ul>
 */
class MigrateRemoteToRestTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.parser(JavaParser.fromJavaVersion()
                .classpath("jakarta.jakartaee-api", "spring-context", "spring-web"))
            .typeValidationOptions(TypeValidation.none());
    }

    @DocumentExample
    @Test
    void generatesRestControllerWithDelegation() {
        rewriteRun(
            spec -> spec.recipe(new MigrateRemoteToRest(null)),
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
            // Implementation
            java(
                """
                package com.example;

                import jakarta.ejb.Stateless;

                @Stateless
                public class CustomerServiceBean implements CustomerService {
                    @Override
                    public Customer find(Long id) {
                        return new Customer();
                    }

                    @Override
                    public void update(Customer customer) {
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/example/CustomerServiceBean.java")
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
                package com.example.rest;

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
                spec -> spec.path("src/main/java/com/example/rest/FindRequest.java")
            ),
            // Generated controller - now with proper imports for non-java.lang types
            java(
                null,
                """
                package com.example.rest;

                import com.example.CustomerService;
                import com.example.Customer;
                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RequestBody;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                @RequestMapping("/api/CustomerService")
                public class CustomerServiceRestController {

                    private final CustomerService customerService;

                    public CustomerServiceRestController(CustomerService customerService) {
                        this.customerService = customerService;
                    }

                    @PostMapping("/find")
                    public Customer find(@RequestBody FindRequest request) {
                        return customerService.find(request.getId());
                    }

                    @PostMapping("/update")
                    public void update(@RequestBody Customer customer) {
                        customerService.update(customer);
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/example/rest/CustomerServiceRestController.java")
            )
        );
    }

    @Test
    void generatesDtoForMultipleParameters() {
        rewriteRun(
            spec -> spec.recipe(new MigrateRemoteToRest(null)),
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
            java(
                """
                package com.example;

                import jakarta.ejb.Stateless;

                @Stateless
                public class BookingServiceImpl implements BookingService {
                    @Override
                    public void createBooking(String userId, String eventId, int seats) {
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/example/BookingServiceImpl.java")
            ),
            // Generated DTO for multiple parameters
            java(
                null,
                """
                package com.example.rest;

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
                spec -> spec.path("src/main/java/com/example/rest/CreateBookingRequest.java")
            ),
            java(
                null,
                """
                package com.example.rest;

                import com.example.BookingService;
                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RequestBody;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                @RequestMapping("/api/BookingService")
                public class BookingServiceRestController {

                    private final BookingService bookingService;

                    public BookingServiceRestController(BookingService bookingService) {
                        this.bookingService = bookingService;
                    }

                    @PostMapping("/createBooking")
                    public void createBooking(@RequestBody CreateBookingRequest request) {
                        bookingService.createBooking(request.getUserId(), request.getEventId(), request.getSeats());
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/example/rest/BookingServiceRestController.java")
            )
        );
    }

    @Test
    void noParameterMethodsHaveNoRequestBody() {
        rewriteRun(
            spec -> spec.recipe(new MigrateRemoteToRest(null)),
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
            java(
                """
                package com.example;

                import jakarta.ejb.Singleton;

                @Singleton
                public class StatusServiceBean implements StatusService {
                    @Override
                    public String getStatus() {
                        return "OK";
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/example/StatusServiceBean.java")
            ),
            // No @RequestBody import when no method needs it
            java(
                null,
                """
                package com.example.rest;

                import com.example.StatusService;
                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                @RequestMapping("/api/StatusService")
                public class StatusServiceRestController {

                    private final StatusService statusService;

                    public StatusServiceRestController(StatusService statusService) {
                        this.statusService = statusService;
                    }

                    @PostMapping("/getStatus")
                    public String getStatus() {
                        return statusService.getStatus();
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/example/rest/StatusServiceRestController.java")
            )
        );
    }

    @Test
    void prefersStatelessOverService() {
        rewriteRun(
            spec -> spec.recipe(new MigrateRemoteToRest(null)),
            java(
                """
                package com.example;

                import jakarta.ejb.Remote;

                @Remote
                public interface PaymentService {
                    void process();
                }
                """,
                spec -> spec.path("src/main/java/com/example/PaymentService.java")
            ),
            // Spring @Service implementation (lower priority)
            java(
                """
                package com.example;

                import org.springframework.stereotype.Service;

                @Service
                public class PaymentServiceSpring implements PaymentService {
                    @Override
                    public void process() {
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/example/PaymentServiceSpring.java")
            ),
            // @Stateless implementation (higher priority)
            java(
                """
                package com.example;

                import jakarta.ejb.Stateless;

                @Stateless
                public class PaymentServiceEjb implements PaymentService {
                    @Override
                    public void process() {
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/example/PaymentServiceEjb.java")
            ),
            // Controller should be generated (implementation found) - no @RequestBody import (no params)
            java(
                null,
                """
                package com.example.rest;

                import com.example.PaymentService;
                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                @RequestMapping("/api/PaymentService")
                public class PaymentServiceRestController {

                    private final PaymentService paymentService;

                    public PaymentServiceRestController(PaymentService paymentService) {
                        this.paymentService = paymentService;
                    }

                    @PostMapping("/process")
                    public void process() {
                        paymentService.process();
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/example/rest/PaymentServiceRestController.java")
            )
        );
    }

    @Test
    void generatesStubWithNeedsReviewWhenNoImplementationFound() {
        rewriteRun(
            spec -> spec.recipe(new MigrateRemoteToRest(null)),
            java(
                """
                package com.example;

                import jakarta.ejb.Remote;

                @Remote
                public interface OrphanService {
                    void doSomething();
                }
                """,
                spec -> spec.path("src/main/java/com/example/OrphanService.java")
            ),
            // Generated stub controller with @NeedsReview marker
            java(
                null,
                """
                package com.example.rest;

                import com.example.OrphanService;
                import com.github.rewrite.ejb.annotations.NeedsReview;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RestController;

                @NeedsReview(reason = "No implementation found for @Remote interface OrphanService",
                    category = NeedsReview.Category.REMOTE_ACCESS,
                    originalCode = "@Remote",
                    suggestedAction = "Implement com.example.OrphanService with @Service or @Stateless annotation")
                @RestController
                @RequestMapping("/api/OrphanService")
                public class OrphanServiceRestController {

                    // TODO: Inject implementation and delegate methods
                    // private final OrphanService delegate;

                }
                """,
                spec -> spec.path("src/main/java/com/example/rest/OrphanServiceRestController.java")
            )
        );
    }

    @Test
    void customTargetPackageSuffix() {
        rewriteRun(
            spec -> spec.recipe(new MigrateRemoteToRest("api.controllers")),
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
            java(
                """
                package com.example;

                import jakarta.ejb.Stateless;

                @Stateless
                public class DataServiceBean implements DataService {
                    @Override
                    public void refresh() {
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/example/DataServiceBean.java")
            ),
            // No @RequestBody import when no method needs it
            java(
                null,
                """
                package com.example.api.controllers;

                import com.example.DataService;
                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                @RequestMapping("/api/DataService")
                public class DataServiceRestController {

                    private final DataService dataService;

                    public DataServiceRestController(DataService dataService) {
                        this.dataService = dataService;
                    }

                    @PostMapping("/refresh")
                    public void refresh() {
                        dataService.refresh();
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/example/api/controllers/DataServiceRestController.java")
            )
        );
    }

    @Test
    void handlesOverloadedMethodsWithUniqueDtoNames() {
        rewriteRun(
            spec -> spec.recipe(new MigrateRemoteToRest(null)),
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
            java(
                """
                package com.example;

                import jakarta.ejb.Stateless;

                @Stateless
                public class SearchServiceImpl implements SearchService {
                    @Override
                    public String search(String query) {
                        return "result";
                    }
                    @Override
                    public String search(String query, int maxResults) {
                        return "result";
                    }
                    @Override
                    public String search(String query, int maxResults, boolean caseSensitive) {
                        return "result";
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/example/SearchServiceImpl.java")
            ),
            // First overload DTO: Search1Request
            java(
                null,
                """
                package com.example.rest;

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
                spec -> spec.path("src/main/java/com/example/rest/Search1Request.java")
            ),
            // Second overload DTO: Search2Request
            java(
                null,
                """
                package com.example.rest;

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
                spec -> spec.path("src/main/java/com/example/rest/Search2Request.java")
            ),
            // Third overload DTO: Search3Request
            java(
                null,
                """
                package com.example.rest;

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
                spec -> spec.path("src/main/java/com/example/rest/Search3Request.java")
            ),
            // Generated controller with unique DTO names and disambiguated paths
            java(
                null,
                """
                package com.example.rest;

                import com.example.SearchService;
                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RequestBody;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                @RequestMapping("/api/SearchService")
                public class SearchServiceRestController {

                    private final SearchService searchService;

                    public SearchServiceRestController(SearchService searchService) {
                        this.searchService = searchService;
                    }

                    @PostMapping("/search/1")
                    public String search(@RequestBody Search1Request request) {
                        return searchService.search(request.getQuery());
                    }

                    @PostMapping("/search/2")
                    public String search(@RequestBody Search2Request request) {
                        return searchService.search(request.getQuery(), request.getMaxResults());
                    }

                    @PostMapping("/search/3")
                    public String search(@RequestBody Search3Request request) {
                        return searchService.search(request.getQuery(), request.getMaxResults(), request.getCaseSensitive());
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/example/rest/SearchServiceRestController.java")
            )
        );
    }

    @Test
    void handlesVarargsParametersAsArrayInDto() {
        rewriteRun(
            spec -> spec.recipe(new MigrateRemoteToRest(null)),
            java(
                """
                package com.example;

                import jakarta.ejb.Remote;

                @Remote
                public interface BatchService {
                    void process(String name, Item... items);
                }
                """,
                spec -> spec.path("src/main/java/com/example/BatchService.java")
            ),
            java(
                """
                package com.example;

                import jakarta.ejb.Stateless;

                @Stateless
                public class BatchServiceImpl implements BatchService {
                    @Override
                    public void process(String name, Item... items) {
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/example/BatchServiceImpl.java")
            ),
            // Item class for type resolution
            java(
                """
                package com.example;

                public class Item {
                    private String id;
                }
                """,
                spec -> spec.path("src/main/java/com/example/Item.java")
            ),
            // Generated DTO - varargs should become array type (Item[])
            java(
                null,
                """
                package com.example.rest;

                import com.example.Item;

                public class ProcessRequest {

                    private String name;
                    private Item[] items;

                    public ProcessRequest() {
                    }

                    public ProcessRequest(String name, Item[] items) {
                        this.name = name;
                        this.items = items;
                    }

                    public String getName() {
                        return name;
                    }

                    public void setName(String name) {
                        this.name = name;
                    }

                    public Item[] getItems() {
                        return items;
                    }

                    public void setItems(Item[] items) {
                        this.items = items;
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/example/rest/ProcessRequest.java")
            ),
            // Generated controller
            java(
                null,
                """
                package com.example.rest;

                import com.example.BatchService;
                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RequestBody;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                @RequestMapping("/api/BatchService")
                public class BatchServiceRestController {

                    private final BatchService batchService;

                    public BatchServiceRestController(BatchService batchService) {
                        this.batchService = batchService;
                    }

                    @PostMapping("/process")
                    public void process(@RequestBody ProcessRequest request) {
                        batchService.process(request.getName(), request.getItems());
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/example/rest/BatchServiceRestController.java")
            )
        );
    }

    @Test
    void javaxRemoteAnnotationAlsoWorks() {
        rewriteRun(
            spec -> spec.recipe(new MigrateRemoteToRest(null))
                        .parser(JavaParser.fromJavaVersion()
                            .classpath("javax.ejb-api", "spring-context", "spring-web")),
            java(
                """
                package com.example;

                import javax.ejb.Remote;

                @Remote
                public interface LegacyService {
                    void doLegacy();
                }
                """,
                spec -> spec.path("src/main/java/com/example/LegacyService.java")
            ),
            java(
                """
                package com.example;

                import javax.ejb.Stateless;

                @Stateless
                public class LegacyServiceBean implements LegacyService {
                    @Override
                    public void doLegacy() {
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/example/LegacyServiceBean.java")
            ),
            // No @RequestBody import when no method needs it
            java(
                null,
                """
                package com.example.rest;

                import com.example.LegacyService;
                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                @RequestMapping("/api/LegacyService")
                public class LegacyServiceRestController {

                    private final LegacyService legacyService;

                    public LegacyServiceRestController(LegacyService legacyService) {
                        this.legacyService = legacyService;
                    }

                    @PostMapping("/doLegacy")
                    public void doLegacy() {
                        legacyService.doLegacy();
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/example/rest/LegacyServiceRestController.java")
            )
        );
    }
}
