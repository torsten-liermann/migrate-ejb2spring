# Jakarta Batch zu Spring Batch – Migrationshinweise

Das Rezept `MarkJakartaBatchForMigration` markiert Klassen mit Jakarta‑Batch‑APIs durch `@NeedsReview`. Die folgenden Zuordnungen dienen als Orientierung für die Migration und sind als Beispiele zu verstehen.

## Begriffszuordnung (Orientierung)

| Jakarta Batch (JSR‑352) | Spring Batch |
|---|---|
| `JobOperator` | `JobLauncher` (Start) und `JobOperator` (Restart/Stop/Query) |
| `job.xml` (JSL) | Java‑Konfiguration (`@Bean`) |
| `@BatchProperty` | `@Value("#{jobParameters['key']}")` mit `@StepScope` |
| `ItemReader<T>` | `ItemReader<T>` |
| `ItemProcessor<I,O>` | `ItemProcessor<I,O>` |
| `ItemWriter<T>` | `ItemWriter<T>` |
| `@Named` Batchlet | `Tasklet` |
| Partition in JSL | `Partitioner` + Java‑Konfiguration |

## Beispiel: JSL‑Job zu Spring‑Batch‑Konfiguration

.Beispiel: JSL (Jakarta Batch)
```xml
<job id="importJob" xmlns="http://xmlns.jcp.org/xml/ns/javaee">
    <step id="importStep">
        <chunk item-count="10">
            <reader ref="csvReader"/>
            <processor ref="customerProcessor"/>
            <writer ref="jpaWriter"/>
        </chunk>
    </step>
</job>
```

.Beispiel: Spring Batch (Java‑Konfiguration)
```java
@Configuration
public class BatchConfig {

    @Bean
    public Job importJob(JobRepository jobRepository, Step importStep) {
        return new JobBuilder("importJob", jobRepository)
            .start(importStep)
            .build();
    }

    @Bean
    public Step importStep(JobRepository jobRepository,
                           PlatformTransactionManager txManager,
                           FlatFileItemReader<CustomerInput> reader,
                           CustomerProcessor processor,
                           JpaItemWriter<Customer> writer) {
        return new StepBuilder("importStep", jobRepository)
            .<CustomerInput, Customer>chunk(10, txManager)
            .reader(reader)
            .processor(processor)
            .writer(writer)
            .build();
    }
}
```

## Beispiel: @BatchProperty zu Job‑Parametern

.Beispiel: Jakarta Batch
```java
@Inject
@BatchProperty(name = "inputFile")
private String inputFile;
```

.Beispiel: Spring Batch
```java
@Bean
@StepScope
public FlatFileItemReader<Data> reader(
        @Value("#{jobParameters['inputFile']}") String inputFile) {
    return new FlatFileItemReaderBuilder<Data>()
        .name("reader")
        .resource(new FileSystemResource(inputFile))
        .build();
}
```

## Abhängigkeiten

Für Spring Batch wird üblicherweise `spring-boot-starter-batch` verwendet. Ob und wie diese Abhängigkeit im Zielsystem eingebunden wird, ist projektspezifisch zu entscheiden.

## Quellen

- https://docs.spring.io/spring-batch/docs/current/reference/html/
- https://jakarta.ee/specifications/batch/
