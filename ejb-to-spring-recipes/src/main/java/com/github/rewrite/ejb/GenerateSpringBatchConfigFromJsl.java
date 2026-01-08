package com.github.rewrite.ejb;

import com.github.rewrite.ejb.config.ProjectConfiguration;
import com.github.rewrite.ejb.config.ProjectConfigurationLoader;
import lombok.EqualsAndHashCode;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.SourceFile;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.text.PlainText;
import org.openrewrite.xml.tree.Content;
import org.openrewrite.xml.tree.Xml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Generates Spring Batch configuration stubs from Jakarta Batch JSL job XML files.
 * <p>
 * This recipe scans job XMLs (typically under META-INF/batch-jobs) and generates
 * a BatchJobsConfiguration class with Job/Step bean definitions.
 * <p>
 * The generated configuration is annotated with @NeedsReview because JSL features
 * (listeners, flows, partitioning, and properties) require manual verification.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class GenerateSpringBatchConfigFromJsl extends ScanningRecipe<GenerateSpringBatchConfigFromJsl.Accumulator> {

    private static final String NEEDS_REVIEW_FQN = "com.github.rewrite.ejb.annotations.NeedsReview";

    @Override
    public String getDisplayName() {
        return "Generate Spring Batch config from JSL job XML";
    }

    @Override
    public String getDescription() {
        return "Creates Spring Batch @Configuration stubs from Jakarta Batch JSL XML files.";
    }

    static class Accumulator {
        Map<String, ModuleInfo> modules = new LinkedHashMap<>();
        Set<Path> existingSourcePaths = new LinkedHashSet<>();
    }

    static class ModuleInfo {
        final String mainSourceRoot;
        final Set<String> packages = new LinkedHashSet<>();
        final List<JobInfo> jobs = new ArrayList<>();

        ModuleInfo(String mainSourceRoot) {
            this.mainSourceRoot = mainSourceRoot;
        }
    }

    static class JobInfo {
        final String jobId;
        final String sourcePath;
        final List<StepInfo> steps;
        final boolean complexFlow;
        final List<String> listeners;
        final boolean hasProperties;

        JobInfo(String jobId, String sourcePath, List<StepInfo> steps, boolean complexFlow,
                List<String> listeners, boolean hasProperties) {
            this.jobId = jobId;
            this.sourcePath = sourcePath;
            this.steps = steps;
            this.complexFlow = complexFlow;
            this.listeners = listeners;
            this.hasProperties = hasProperties;
        }
    }

    static class StepInfo {
        final String stepId;
        final ChunkInfo chunk;
        final BatchletInfo batchlet;
        final boolean complexStep;
        final List<String> listeners;
        final List<String> partitionRefs;
        final boolean hasProperties;

        StepInfo(String stepId, ChunkInfo chunk, BatchletInfo batchlet, boolean complexStep,
                 List<String> listeners, List<String> partitionRefs, boolean hasProperties) {
            this.stepId = stepId;
            this.chunk = chunk;
            this.batchlet = batchlet;
            this.complexStep = complexStep;
            this.listeners = listeners;
            this.partitionRefs = partitionRefs;
            this.hasProperties = hasProperties;
        }
    }

    static class ChunkInfo {
        final Integer itemCount;
        final String readerRef;
        final String processorRef;
        final String writerRef;
        final boolean missingReaderWriter;

        ChunkInfo(@Nullable Integer itemCount, @Nullable String readerRef, @Nullable String processorRef,
                  @Nullable String writerRef, boolean missingReaderWriter) {
            this.itemCount = itemCount;
            this.readerRef = readerRef;
            this.processorRef = processorRef;
            this.writerRef = writerRef;
            this.missingReaderWriter = missingReaderWriter;
        }
    }

    static class BatchletInfo {
        final String ref;
        final boolean missingRef;

        BatchletInfo(@Nullable String ref, boolean missingRef) {
            this.ref = ref;
            this.missingRef = missingRef;
        }
    }

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public Tree visit(Tree tree, ExecutionContext ctx) {
                if (tree instanceof SourceFile) {
                    SourceFile sourceFile = (SourceFile) tree;
                    Path sourcePath = sourceFile.getSourcePath();
                    if (sourcePath != null) {
                        acc.existingSourcePaths.add(sourcePath);
                    }

                    if (sourceFile instanceof org.openrewrite.java.tree.J.CompilationUnit) {
                        org.openrewrite.java.tree.J.CompilationUnit cu =
                                (org.openrewrite.java.tree.J.CompilationUnit) sourceFile;
                        String pathString = normalizePath(sourcePath);
                        ProjectConfiguration config = ProjectConfigurationLoader.loadWithInheritance(
                                extractProjectRoot(sourcePath));
                        if (!config.isMainSource(pathString)) {
                            return tree;
                        }
                        String mainSourceRoot = extractMainSourceRoot(pathString, config);
                        ModuleInfo module = acc.modules.computeIfAbsent(
                                mainSourceRoot, ModuleInfo::new);
                        if (cu.getPackageDeclaration() != null) {
                            module.packages.add(cu.getPackageDeclaration().getPackageName());
                        }
                        return tree;
                    }

                    if (sourceFile instanceof Xml.Document) {
                        Xml.Document doc = (Xml.Document) sourceFile;
                        String pathString = normalizePath(sourcePath);
                        ProjectConfiguration config = ProjectConfigurationLoader.loadWithInheritance(
                                extractProjectRoot(sourcePath));
                        if (!config.isResource(pathString) || isTestResource(pathString, config)) {
                            return tree;
                        }
                        JobInfo job = parseJobXml(doc, pathString);
                        if (job == null) {
                            return tree;
                        }
                        String mainSourceRoot = deriveMainSourceRootFromResource(pathString, config);
                        ModuleInfo module = acc.modules.computeIfAbsent(
                                mainSourceRoot, ModuleInfo::new);
                        module.jobs.add(job);
                    }
                }
                return tree;
            }
        };
    }

    @Override
    public Collection<? extends SourceFile> generate(Accumulator acc, ExecutionContext ctx) {
        if (acc.modules.isEmpty()) {
            return Collections.emptyList();
        }

        List<SourceFile> generated = new ArrayList<>();
        for (ModuleInfo module : acc.modules.values()) {
            if (module.jobs.isEmpty()) {
                continue;
            }
            String packageName = commonPackagePrefix(module.packages);
            boolean packageFallback = false;
            if (packageName.isEmpty()) {
                packageFallback = true;
                if (!module.packages.isEmpty()) {
                    packageName = module.packages.iterator().next();
                } else {
                    packageName = "batch";
                }
            }

            String className = "BatchJobsConfiguration";
            String packagePath = packageName.isEmpty() ? "" : packageName.replace('.', '/');
            Path targetPath = packagePath.isEmpty()
                ? Paths.get(module.mainSourceRoot, className + ".java")
                : Paths.get(module.mainSourceRoot, packagePath, className + ".java");

            if (acc.existingSourcePaths.contains(targetPath)) {
                continue;
            }

            String content = generateBatchConfig(module, packageName, className, packageFallback);
            generated.add(PlainText.builder()
                .sourcePath(targetPath)
                .text(content)
                .build());
        }

        return generated;
    }

    private String generateBatchConfig(ModuleInfo module, String packageName, String className,
                                       boolean packageFallback) {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(packageName).append(";\n\n");
        sb.append("import ").append(NEEDS_REVIEW_FQN).append(";\n");
        sb.append("import org.springframework.batch.core.Job;\n");
        sb.append("import org.springframework.batch.core.Step;\n");
        sb.append("import org.springframework.batch.core.job.builder.JobBuilder;\n");
        sb.append("import org.springframework.batch.core.repository.JobRepository;\n");
        sb.append("import org.springframework.batch.core.step.builder.StepBuilder;\n");
        sb.append("import org.springframework.batch.core.step.tasklet.Tasklet;\n");
        sb.append("import org.springframework.batch.item.ItemProcessor;\n");
        sb.append("import org.springframework.batch.item.ItemReader;\n");
        sb.append("import org.springframework.batch.item.ItemWriter;\n");
        sb.append("import org.springframework.beans.factory.annotation.Qualifier;\n");
        sb.append("import org.springframework.context.annotation.Bean;\n");
        sb.append("import org.springframework.context.annotation.Configuration;\n");
        sb.append("import org.springframework.transaction.PlatformTransactionManager;\n");
        sb.append("\n");
        sb.append("@NeedsReview(reason = \"Generated from JSL batch job XML; verify Spring Batch mapping\", ");
        sb.append("category = NeedsReview.Category.MANUAL_MIGRATION, ");
        sb.append("originalCode = \"").append(escapeJava(joinJobSources(module.jobs))).append("\", ");
        sb.append("suggestedAction = \"Review job flow, chunk settings, listeners, and transaction semantics.\")\n");
        sb.append("@Configuration\n");
        sb.append("public class ").append(className).append(" {\n\n");

        if (packageFallback) {
            sb.append("    // TODO: Package selection was inferred; consider moving this config to the desired base package.\n\n");
        }

        for (JobInfo job : module.jobs) {
            String jobMethod = sanitizeIdentifier(job.jobId, "batchJob");
            String jobIdLiteral = job.jobId != null ? job.jobId : jobMethod;
            List<String> stepMethods = new ArrayList<>();
            for (int i = 0; i < job.steps.size(); i++) {
                StepInfo step = job.steps.get(i);
                stepMethods.add(buildStepMethodName(job.jobId, step.stepId, i));
            }
            String firstStepMethod = stepMethods.isEmpty() ? null : stepMethods.get(0);

            sb.append("    @Bean\n");
            if (firstStepMethod != null) {
                sb.append("    public Job ").append(jobMethod)
                    .append("(JobRepository jobRepository, Step ")
                    .append(firstStepMethod).append(") {\n");
                if (!job.listeners.isEmpty()) {
                    sb.append("        // TODO: JSL job listeners: ")
                        .append(String.join(", ", job.listeners)).append("\n");
                }
                if (job.hasProperties) {
                    sb.append("        // TODO: JSL job properties present; map to JobParameters/ExecutionContext.\n");
                }
                sb.append("        return new JobBuilder(\"")
                    .append(jobIdLiteral).append("\", jobRepository)\n")
                    .append("            .start(").append(firstStepMethod).append(")\n");
                if (!job.complexFlow) {
                    for (int i = 1; i < stepMethods.size(); i++) {
                        sb.append("            .next(").append(stepMethods.get(i)).append(")\n");
                    }
                } else {
                    sb.append("            // TODO: Job uses transitions (next/stop/end/fail). Map flow explicitly.\n");
                }
                sb.append("            .build();\n");
                sb.append("    }\n\n");
            } else {
                sb.append("    public Job ").append(jobMethod)
                    .append("(JobRepository jobRepository) {\n");
                if (!job.listeners.isEmpty()) {
                    sb.append("        // TODO: JSL job listeners: ")
                        .append(String.join(", ", job.listeners)).append("\n");
                }
                if (job.hasProperties) {
                    sb.append("        // TODO: JSL job properties present; map to JobParameters/ExecutionContext.\n");
                }
                sb.append("        // TODO: No <step> elements detected in ").append(job.sourcePath).append("\n");
                sb.append("        return new JobBuilder(\"")
                    .append(jobIdLiteral).append("\", jobRepository)\n")
                    .append("            .start(null)\n")
                    .append("            .build();\n");
                sb.append("    }\n\n");
            }

            for (int i = 0; i < job.steps.size(); i++) {
                appendStepBean(sb, stepMethods.get(i), job.steps.get(i), i);
            }

            if (job.complexFlow) {
                sb.append("    // TODO: Job flow contains transitions or split/flow/decision elements; verify mapping.\n\n");
            }
        }

        sb.append("}\n");
        return sb.toString();
    }

    private void appendStepBean(StringBuilder sb, String stepMethod, StepInfo step, int index) {
        String fallbackStepId = "step" + (index + 1);
        String stepIdLiteral = step.stepId != null ? step.stepId : fallbackStepId;

        sb.append("    @Bean\n");
        sb.append("    public Step ").append(stepMethod)
            .append("(JobRepository jobRepository, PlatformTransactionManager transactionManager");

        List<String> paramNames = new ArrayList<>();
        if (step.chunk != null) {
            String readerParam = appendReaderParam(sb, step.chunk);
            if (readerParam != null) {
                paramNames.add(readerParam);
            }
            String processorParam = appendProcessorParam(sb, step.chunk);
            if (processorParam != null) {
                paramNames.add(processorParam);
            }
            String writerParam = appendWriterParam(sb, step.chunk);
            if (writerParam != null) {
                paramNames.add(writerParam);
            }
        } else if (step.batchlet != null) {
            String batchletParam = appendBatchletParam(sb, step.batchlet);
            if (batchletParam != null) {
                paramNames.add(batchletParam);
            }
        }

        sb.append(") {\n");
        if (!step.listeners.isEmpty()) {
            sb.append("        // TODO: JSL step listeners: ")
                .append(String.join(", ", step.listeners)).append("\n");
        }
        if (!step.partitionRefs.isEmpty()) {
            sb.append("        // TODO: JSL partition elements: ")
                .append(String.join(", ", step.partitionRefs)).append("\n");
        }
        if (step.hasProperties) {
            sb.append("        // TODO: JSL step properties present; map to ExecutionContext/StepScope.\n");
        }
        sb.append("        return new StepBuilder(\"").append(stepIdLiteral)
            .append("\", jobRepository)\n");

        if (step.chunk != null) {
            int chunkSize = step.chunk.itemCount != null ? step.chunk.itemCount : 1;
            if (step.chunk.itemCount == null) {
                sb.append("            // TODO: item-count missing; verify chunk size\n");
            }
            if (step.chunk.readerRef == null || step.chunk.writerRef == null) {
                sb.append("            // TODO: JSL chunk missing ");
                if (step.chunk.readerRef == null && step.chunk.writerRef == null) {
                    sb.append("reader and writer refs\n");
                } else if (step.chunk.readerRef == null) {
                    sb.append("reader ref\n");
                } else {
                    sb.append("writer ref\n");
                }
            }
            sb.append("            .<Object, Object>chunk(").append(chunkSize)
                .append(", transactionManager)\n");
            String readerName = paramNames.size() > 0 ? paramNames.get(0) : null;
            if (readerName != null) {
                sb.append("            .reader(").append(readerName).append(")\n");
            }
            if (step.chunk.processorRef != null) {
                String processorName = findParamName(paramNames, step.chunk.processorRef);
                if (processorName != null) {
                    sb.append("            .processor(").append(processorName).append(")\n");
                }
            }
            String writerName = paramNames.size() > 0 ? paramNames.get(paramNames.size() - 1) : null;
            if (writerName != null) {
                sb.append("            .writer(").append(writerName).append(")\n");
            }
            sb.append("            .build();\n");
        } else if (step.batchlet != null) {
            String taskletName = paramNames.isEmpty() ? null : paramNames.get(0);
            if (taskletName == null) {
                if (step.batchlet.missingRef) {
                    sb.append("            // TODO: JSL batchlet missing ref attribute\n");
                }
                sb.append("            // TODO: Batchlet bean not resolved\n");
                sb.append("            .tasklet(null, transactionManager)\n");
            } else {
                sb.append("            .tasklet(").append(taskletName).append(", transactionManager)\n");
            }
            sb.append("            .build();\n");
        } else {
            sb.append("            // TODO: Unsupported step type (neither chunk nor batchlet)\n");
            sb.append("            .build();\n");
        }

        sb.append("    }\n\n");

        if (step.complexStep) {
            sb.append("    // TODO: Step contains transitions or partition/listener/properties elements; verify mapping.\n\n");
        }
    }

    private String appendReaderParam(StringBuilder sb, ChunkInfo chunk) {
        String ref = chunk.readerRef != null ? chunk.readerRef : "reader";
        sb.append(", ");
        if (chunk.readerRef != null) {
            sb.append("@Qualifier(\"").append(chunk.readerRef).append("\") ");
        }
        sb.append("ItemReader<Object> ").append(sanitizeIdentifier(ref, "reader"));
        return sanitizeIdentifier(ref, "reader");
    }

    private String appendProcessorParam(StringBuilder sb, ChunkInfo chunk) {
        if (chunk.processorRef == null) {
            return null;
        }
        String ref = chunk.processorRef;
        sb.append(", @Qualifier(\"").append(ref).append("\") ")
            .append("ItemProcessor<Object, Object> ")
            .append(sanitizeIdentifier(ref, "processor"));
        return sanitizeIdentifier(ref, "processor");
    }

    private String appendWriterParam(StringBuilder sb, ChunkInfo chunk) {
        String ref = chunk.writerRef != null ? chunk.writerRef : "writer";
        sb.append(", ");
        if (chunk.writerRef != null) {
            sb.append("@Qualifier(\"").append(chunk.writerRef).append("\") ");
        }
        sb.append("ItemWriter<Object> ").append(sanitizeIdentifier(ref, "writer"));
        return sanitizeIdentifier(ref, "writer");
    }

    private String appendBatchletParam(StringBuilder sb, BatchletInfo batchlet) {
        if (batchlet.missingRef) {
            return null;
        }
        String ref = batchlet.ref;
        sb.append(", ");
        sb.append("@Qualifier(\"").append(ref).append("\") ");
        sb.append("Tasklet ").append(sanitizeIdentifier(ref, "tasklet"));
        return sanitizeIdentifier(ref, "tasklet");
    }

    private static @Nullable JobInfo parseJobXml(Xml.Document doc, String sourcePath) {
        Xml.Tag root = doc.getRoot();
        if (root == null || !"job".equals(root.getName())) {
            return null;
        }

        String jobId = attributeValue(root, "id");
        if (jobId == null || jobId.isBlank()) {
            jobId = deriveJobIdFromPath(sourcePath);
        }

        List<StepInfo> steps = new ArrayList<>();
        boolean complexFlow = false;
        List<String> jobListeners = new ArrayList<>();
        boolean jobHasProperties = false;

        for (Content content : root.getContent()) {
            if (content instanceof Xml.Tag) {
                Xml.Tag tag = (Xml.Tag) content;
                String name = tag.getName();
                if ("listeners".equals(name)) {
                    jobListeners.addAll(parseListenerRefs(tag));
                } else if ("properties".equals(name)) {
                    jobHasProperties = true;
                } else if ("step".equals(name)) {
                    if (hasStepTransitions(tag)) {
                        complexFlow = true;
                    }
                    steps.add(parseStep(tag));
                } else if ("split".equals(name) || "flow".equals(name) || "decision".equals(name)) {
                    complexFlow = true;
                }
            }
        }

        return new JobInfo(jobId, sourcePath, steps, complexFlow, jobListeners, jobHasProperties);
    }

    private static StepInfo parseStep(Xml.Tag stepTag) {
        String stepId = attributeValue(stepTag, "id");
        ChunkInfo chunk = null;
        BatchletInfo batchlet = null;
        boolean complexStep = false;
        List<String> listeners = new ArrayList<>();
        List<String> partitionRefs = new ArrayList<>();
        boolean hasProperties = false;

        for (Content content : stepTag.getContent()) {
            if (!(content instanceof Xml.Tag)) {
                continue;
            }
            Xml.Tag tag = (Xml.Tag) content;
            String name = tag.getName();
            if ("chunk".equals(name)) {
                chunk = parseChunk(tag);
            } else if ("batchlet".equals(name)) {
                batchlet = parseBatchlet(tag);
            } else if ("next".equals(name) || "stop".equals(name) || "end".equals(name) || "fail".equals(name)) {
                complexStep = true;
            } else if ("partition".equals(name)) {
                partitionRefs.addAll(parsePartitionRefs(tag));
                complexStep = true;
            } else if ("listeners".equals(name)) {
                listeners.addAll(parseListenerRefs(tag));
                complexStep = true;
            } else if ("properties".equals(name)) {
                hasProperties = true;
                complexStep = true;
            }
        }

        if (hasStepTransitions(stepTag)) {
            complexStep = true;
        }

        return new StepInfo(stepId, chunk, batchlet, complexStep, listeners, partitionRefs, hasProperties);
    }

    private static ChunkInfo parseChunk(Xml.Tag chunkTag) {
        Integer itemCount = null;
        String itemCountValue = attributeValue(chunkTag, "item-count");
        if (itemCountValue != null) {
            try {
                itemCount = Integer.parseInt(itemCountValue.trim());
            } catch (NumberFormatException ignored) {
                itemCount = null;
            }
        }

        String readerRef = null;
        String processorRef = null;
        String writerRef = null;

        for (Content content : chunkTag.getContent()) {
            if (!(content instanceof Xml.Tag)) {
                continue;
            }
            Xml.Tag tag = (Xml.Tag) content;
            String name = tag.getName();
            if ("reader".equals(name)) {
                readerRef = attributeValue(tag, "ref");
            } else if ("processor".equals(name)) {
                processorRef = attributeValue(tag, "ref");
            } else if ("writer".equals(name)) {
                writerRef = attributeValue(tag, "ref");
            }
        }

        boolean missingReaderWriter = readerRef == null || writerRef == null;
        return new ChunkInfo(itemCount, readerRef, processorRef, writerRef, missingReaderWriter);
    }

    private static BatchletInfo parseBatchlet(Xml.Tag batchletTag) {
        String ref = attributeValue(batchletTag, "ref");
        boolean missingRef = ref == null;
        return new BatchletInfo(ref, missingRef);
    }

    private static List<String> parseListenerRefs(Xml.Tag listenersTag) {
        Set<String> refs = new LinkedHashSet<>();
        for (Content content : listenersTag.getContent()) {
            if (!(content instanceof Xml.Tag)) {
                continue;
            }
            Xml.Tag tag = (Xml.Tag) content;
            if (!"listener".equals(tag.getName())) {
                continue;
            }
            String ref = attributeValue(tag, "ref");
            String clazz = attributeValue(tag, "class");
            refs.add(formatTagRef("listener", ref, clazz, null));
        }
        return new ArrayList<>(refs);
    }

    private static List<String> parsePartitionRefs(Xml.Tag partitionTag) {
        Set<String> refs = new LinkedHashSet<>();
        for (Content content : partitionTag.getContent()) {
            if (!(content instanceof Xml.Tag)) {
                continue;
            }
            Xml.Tag tag = (Xml.Tag) content;
            String name = tag.getName();
            String ref = attributeValue(tag, "ref");
            String clazz = attributeValue(tag, "class");
            String partitionCount = attributeValue(tag, "partition-count");
            refs.add(formatTagRef(name, ref, clazz, partitionCount));
        }
        return new ArrayList<>(refs);
    }

    private static String formatTagRef(String name, @Nullable String ref, @Nullable String clazz,
                                       @Nullable String partitionCount) {
        if (ref != null) {
            return name + "(ref=" + ref + ")";
        }
        if (clazz != null) {
            return name + "(class=" + clazz + ")";
        }
        if (partitionCount != null) {
            return name + "(partition-count=" + partitionCount + ")";
        }
        return name;
    }

    private static String attributeValue(Xml.Tag tag, String attributeName) {
        for (Xml.Attribute attr : tag.getAttributes()) {
            if (attributeName.equals(attr.getKeyAsString())) {
                return attr.getValueAsString();
            }
        }
        return null;
    }

    private static boolean hasStepTransitions(Xml.Tag stepTag) {
        for (Xml.Attribute attr : stepTag.getAttributes()) {
            if ("next".equals(attr.getKeyAsString())) {
                return true;
            }
        }
        for (Content content : stepTag.getContent()) {
            if (content instanceof Xml.Tag) {
                String name = ((Xml.Tag) content).getName();
                if ("next".equals(name) || "stop".equals(name) || "end".equals(name) || "fail".equals(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String deriveJobIdFromPath(String sourcePath) {
        if (sourcePath == null) {
            return "batchJob";
        }
        String fileName = sourcePath.replace('\\', '/');
        int idx = fileName.lastIndexOf('/');
        if (idx >= 0 && idx + 1 < fileName.length()) {
            fileName = fileName.substring(idx + 1);
        }
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            fileName = fileName.substring(0, dot);
        }
        if (fileName.isBlank()) {
            return "batchJob";
        }
        return fileName;
    }

    private static String joinJobSources(List<JobInfo> jobs) {
        if (jobs == null || jobs.isEmpty()) {
            return "JSL batch job XML";
        }
        List<String> sources = new ArrayList<>();
        for (JobInfo job : jobs) {
            if (job.sourcePath != null && !job.sourcePath.isBlank()) {
                sources.add(job.sourcePath);
            }
        }
        if (sources.isEmpty()) {
            return "JSL batch job XML";
        }
        return String.join(", ", sources);
    }

    private static String escapeJava(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\\\"");
    }

    private static String commonPackagePrefix(Set<String> packages) {
        if (packages == null || packages.isEmpty()) {
            return "";
        }
        String first = packages.iterator().next();
        if (packages.size() == 1) {
            return first;
        }
        String[] baseParts = first.split("\\.");
        int commonLength = baseParts.length;
        for (String pkg : packages) {
            String[] parts = pkg.split("\\.");
            commonLength = Math.min(commonLength, parts.length);
            for (int i = 0; i < commonLength; i++) {
                if (!baseParts[i].equals(parts[i])) {
                    commonLength = i;
                    break;
                }
            }
            if (commonLength == 0) {
                break;
            }
        }
        if (commonLength == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < commonLength; i++) {
            if (i > 0) {
                sb.append('.');
            }
            sb.append(baseParts[i]);
        }
        return sb.toString();
    }

    private static String sanitizeIdentifier(@Nullable String raw, String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        StringBuilder sb = new StringBuilder();
        String normalized = raw.trim();
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (Character.isLetterOrDigit(ch) || ch == '_') {
                sb.append(ch);
            } else {
                sb.append('_');
            }
        }
        if (sb.length() == 0) {
            return fallback;
        }
        if (!Character.isJavaIdentifierStart(sb.charAt(0))) {
            sb.insert(0, '_');
        }
        return sb.toString();
    }

    private static String buildStepMethodName(@Nullable String jobId, @Nullable String stepId, int index) {
        String jobPart = sanitizeIdentifier(jobId, "job");
        String stepPart = sanitizeIdentifier(stepId, "step" + (index + 1));
        return jobPart + "_" + stepPart;
    }

    private static @Nullable String findParamName(List<String> paramNames, String ref) {
        String sanitized = sanitizeIdentifier(ref, ref);
        for (String name : paramNames) {
            if (name.equals(sanitized)) {
                return name;
            }
        }
        return null;
    }

    private static String normalizePath(Path sourcePath) {
        if (sourcePath == null) {
            return "";
        }
        return sourcePath.toString().replace('\\', '/');
    }

    private static boolean isTestResource(String sourcePath, ProjectConfiguration config) {
        if (sourcePath == null) {
            return false;
        }
        String normalized = sourcePath.replace('\\', '/');
        for (String root : config.getTestResourceRoots()) {
            String normalizedRoot = root.replace('\\', '/');
            if (normalized.contains("/" + normalizedRoot + "/") || normalized.startsWith(normalizedRoot + "/")) {
                return true;
            }
        }
        return false;
    }

    private static String extractMainSourceRoot(String sourcePath, ProjectConfiguration config) {
        if (sourcePath == null) {
            return "src/main/java";
        }
        String normalizedPath = sourcePath.replace('\\', '/');
        for (String root : config.getMainSourceRoots()) {
            String normalizedRoot = root.replace('\\', '/');
            String marker = "/" + normalizedRoot + "/";
            int markerIndex = normalizedPath.indexOf(marker);
            if (markerIndex >= 0) {
                return normalizedPath.substring(0, markerIndex + marker.length() - 1);
            }
            if (normalizedPath.startsWith(normalizedRoot + "/")) {
                return normalizedRoot;
            }
        }
        return "src/main/java";
    }

    private static String deriveMainSourceRootFromResource(String resourcePath, ProjectConfiguration config) {
        if (resourcePath == null) {
            return "src/main/java";
        }
        String normalized = resourcePath.replace('\\', '/');
        String mainRoot = config.getMainSourceRoots().isEmpty() ? "src/main/java" : config.getMainSourceRoots().get(0);
        for (String root : config.getResourceRoots()) {
            String normalizedRoot = root.replace('\\', '/');
            String marker = "/" + normalizedRoot + "/";
            int idx = normalized.indexOf(marker);
            if (idx >= 0) {
                String modulePrefix = normalized.substring(0, idx);
                if (modulePrefix.isEmpty()) {
                    return mainRoot;
                }
                return modulePrefix + "/" + mainRoot;
            }
            if (normalized.startsWith(normalizedRoot + "/")) {
                return mainRoot;
            }
        }
        return mainRoot;
    }

    private static Path extractProjectRoot(Path sourcePath) {
        if (sourcePath == null) {
            return Paths.get(System.getProperty("user.dir"));
        }
        Path current = sourcePath.toAbsolutePath().getParent();
        while (current != null) {
            if (Files.exists(current.resolve("pom.xml")) ||
                Files.exists(current.resolve("build.gradle")) ||
                Files.exists(current.resolve("build.gradle.kts")) ||
                Files.exists(current.resolve("settings.gradle")) ||
                Files.exists(current.resolve("settings.gradle.kts")) ||
                Files.exists(current.resolve("project.yaml"))) {
                return current;
            }
            current = current.getParent();
        }
        return sourcePath.toAbsolutePath().getParent();
    }
}
