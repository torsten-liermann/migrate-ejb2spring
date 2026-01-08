package com.github.rewrite.ejb;

import org.openrewrite.java.tree.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Utility class for analyzing EJB @Schedule and @Schedules annotations.
 * <p>
 * Provides common functionality for all timer-related recipes:
 * - Parsing @Schedule/@Schedules attributes
 * - Detecting Timer parameters
 * - Detecting non-literal values
 * - Building Spring cron expressions
 * <p>
 * EJB Schedule defaults (per EJB 3.2 spec):
 * - second = "0"
 * - minute = "0"
 * - hour = "0"
 * - dayOfMonth = "*"
 * - month = "*"
 * - dayOfWeek = "*"
 * - year = "*"
 * - persistent = true (IMPORTANT: default is true!)
 */
public final class TimerScheduleUtils {

    public static final String SCHEDULE_FQN = "jakarta.ejb.Schedule";
    public static final String SCHEDULES_FQN = "jakarta.ejb.Schedules";
    public static final String TIMER_FQN = "jakarta.ejb.Timer";
    public static final String TIMER_SERVICE_FQN = "jakarta.ejb.TimerService";

    private TimerScheduleUtils() {
        // Utility class
    }

    /**
     * Configuration extracted from an @Schedule annotation.
     * Default values match EJB 3.2 specification.
     */
    public static class ScheduleConfig {
        // EJB 3.2 defaults
        private String second = "0";
        private String minute = "0";
        private String hour = "0";
        private String dayOfMonth = "*";
        private String month = "*";
        private String dayOfWeek = "*";
        private String year = "*";
        private String timezone = null;
        private Boolean persistent = null;  // null = use EJB default (true)
        private String info = null;
        private boolean hasNonLiterals = false;
        private final Set<String> nonLiteralFields = new LinkedHashSet<>();

        // Getters
        public String getSecond() { return second; }
        public String getMinute() { return minute; }
        public String getHour() { return hour; }
        public String getDayOfMonth() { return dayOfMonth; }
        public String getMonth() { return month; }
        public String getDayOfWeek() { return dayOfWeek; }
        public String getYear() { return year; }
        public String getTimezone() { return timezone; }
        public String getInfo() { return info; }
        public Set<String> getNonLiteralFields() { return Collections.unmodifiableSet(nonLiteralFields); }

        /**
         * Returns the persistent attribute value.
         * IMPORTANT: null means EJB default (true), not false!
         */
        public Boolean getPersistent() { return persistent; }

        /**
         * Returns the effective persistent value (EJB default is true).
         */
        public boolean isEffectivelyPersistent() {
            return persistent == null || persistent;
        }

        public boolean hasNonLiterals() { return hasNonLiterals; }

        /**
         * Returns true if this schedule uses the year attribute with a non-wildcard value.
         * Spring cron has no year field, so these cannot be directly migrated.
         */
        public boolean hasYearRestriction() {
            return year != null && !"*".equals(year);
        }

        /**
         * Returns true if this schedule is safe for automatic migration to @Scheduled.
         * Safe means:
         * - persistent = false explicitly set (EJB default is true)
         * - No non-literal values (constants can't be safely converted)
         * - No year restriction (Spring cron has no year field)
         */
        public boolean isSafeForScheduledMigration() {
            return Boolean.FALSE.equals(persistent) && !hasNonLiterals && !hasYearRestriction();
        }

        /**
         * Builds a Spring cron expression from this configuration.
         * Format: second minute hour dayOfMonth month dayOfWeek
         */
        public String buildSpringCronExpression() {
            return String.format("%s %s %s %s %s %s",
                    second, minute, hour, dayOfMonth, month, dayOfWeek);
        }

        // Package-private setters for parsing
        void setSecond(String second) { this.second = second; }
        void setMinute(String minute) { this.minute = minute; }
        void setHour(String hour) { this.hour = hour; }
        void setDayOfMonth(String dayOfMonth) { this.dayOfMonth = dayOfMonth; }
        void setMonth(String month) { this.month = month; }
        void setDayOfWeek(String dayOfWeek) { this.dayOfWeek = dayOfWeek; }
        void setYear(String year) { this.year = year; }
        void setTimezone(String timezone) { this.timezone = timezone; }
        void setPersistent(Boolean persistent) { this.persistent = persistent; }
        void setInfo(String info) { this.info = info; }
        void setHasNonLiterals(boolean hasNonLiterals) { this.hasNonLiterals = hasNonLiterals; }
        void addNonLiteralField(String field) { this.nonLiteralFields.add(field); }
    }

    /**
     * Result of analyzing a method for Timer-related features.
     */
    public static class TimerAnalysis {
        private final boolean hasTimerParameter;
        private final String timerParameterName;
        private final boolean timerUsedInBody;

        public TimerAnalysis(boolean hasTimerParameter, String timerParameterName, boolean timerUsedInBody) {
            this.hasTimerParameter = hasTimerParameter;
            this.timerParameterName = timerParameterName;
            this.timerUsedInBody = timerUsedInBody;
        }

        public boolean hasTimerParameter() { return hasTimerParameter; }
        public String getTimerParameterName() { return timerParameterName; }
        public boolean isTimerUsedInBody() { return timerUsedInBody; }
    }

    // ========== Annotation Detection ==========

    /**
     * Checks if the annotation is @Schedule (jakarta.ejb.Schedule).
     */
    public static boolean isScheduleAnnotation(J.Annotation ann) {
        if (ann.getType() != null && TypeUtils.isOfClassType(ann.getType(), SCHEDULE_FQN)) {
            return true;
        }
        return "Schedule".equals(ann.getSimpleName());
    }

    /**
     * Checks if the annotation is @Schedules (jakarta.ejb.Schedules).
     */
    public static boolean isSchedulesAnnotation(J.Annotation ann) {
        if (ann.getType() != null && TypeUtils.isOfClassType(ann.getType(), SCHEDULES_FQN)) {
            return true;
        }
        return "Schedules".equals(ann.getSimpleName());
    }

    // ========== Schedule Parsing ==========

    /**
     * Extracts configuration from an @Schedule annotation.
     */
    public static ScheduleConfig extractScheduleConfig(J.Annotation ann) {
        ScheduleConfig config = new ScheduleConfig();

        if (ann.getArguments() == null) {
            return config;
        }

        for (Expression arg : ann.getArguments()) {
            if (arg instanceof J.Assignment) {
                J.Assignment assignment = (J.Assignment) arg;
                if (assignment.getVariable() instanceof J.Identifier) {
                    String name = ((J.Identifier) assignment.getVariable()).getSimpleName();
                    parseScheduleAttribute(config, name, assignment.getAssignment());
                }
            }
        }

        return config;
    }

    private static void parseScheduleAttribute(ScheduleConfig config, String name, Expression value) {
        // Handle persistent attribute (boolean)
        if ("persistent".equals(name)) {
            Boolean boolValue = extractBooleanValue(value);
            if (boolValue != null) {
                config.setPersistent(boolValue);
            }
            return;
        }

        // Handle string attributes
        String stringValue = extractStringValue(value);

        // Check for non-literal values in schedule fields
        if (stringValue == null && isScheduleField(name)) {
            config.setHasNonLiterals(true);
            config.addNonLiteralField(name);
            return;
        }

        if (stringValue != null) {
            switch (name) {
                case "second": config.setSecond(stringValue); break;
                case "minute": config.setMinute(stringValue); break;
                case "hour": config.setHour(stringValue); break;
                case "dayOfMonth": config.setDayOfMonth(stringValue); break;
                case "month": config.setMonth(stringValue); break;
                case "dayOfWeek": config.setDayOfWeek(stringValue); break;
                case "year": config.setYear(stringValue); break;
                case "timezone": config.setTimezone(stringValue); break;
                case "info": config.setInfo(stringValue); break;
            }
        }
    }

    /**
     * Checks if the field name is a schedule timing field (not info/persistent).
     */
    public static boolean isScheduleField(String name) {
        return "second".equals(name) || "minute".equals(name) || "hour".equals(name) ||
               "dayOfMonth".equals(name) || "month".equals(name) || "dayOfWeek".equals(name) ||
               "year".equals(name) || "timezone".equals(name);
    }

    /**
     * Extracts inner @Schedule annotations from a @Schedules container.
     */
    public static List<J.Annotation> extractSchedulesFromContainer(J.Annotation schedulesAnn) {
        List<J.Annotation> result = new ArrayList<>();

        if (schedulesAnn.getArguments() == null) {
            return result;
        }

        for (Expression arg : schedulesAnn.getArguments()) {
            // @Schedules({@Schedule(...), @Schedule(...)}) - the value is a NewArray
            if (arg instanceof J.NewArray) {
                extractFromNewArray((J.NewArray) arg, result);
            }
            // @Schedules(value = {...}) - Assignment form with named parameter
            else if (arg instanceof J.Assignment) {
                J.Assignment assignment = (J.Assignment) arg;
                Expression value = assignment.getAssignment();
                if (value instanceof J.NewArray) {
                    extractFromNewArray((J.NewArray) value, result);
                } else if (value instanceof J.Annotation) {
                    result.add((J.Annotation) value);
                }
            }
            // @Schedules(@Schedule(...)) - single schedule without array
            else if (arg instanceof J.Annotation) {
                result.add((J.Annotation) arg);
            }
        }

        return result;
    }

    private static void extractFromNewArray(J.NewArray array, List<J.Annotation> result) {
        if (array.getInitializer() != null) {
            for (Expression element : array.getInitializer()) {
                if (element instanceof J.Annotation) {
                    result.add((J.Annotation) element);
                }
            }
        }
    }

    // ========== Timer Parameter Analysis ==========

    /**
     * Analyzes a method for Timer parameter presence and usage.
     */
    public static TimerAnalysis analyzeTimerParameter(J.MethodDeclaration method) {
        if (method.getParameters() == null) {
            return new TimerAnalysis(false, null, false);
        }

        for (Statement param : method.getParameters()) {
            if (param instanceof J.VariableDeclarations) {
                J.VariableDeclarations varDecl = (J.VariableDeclarations) param;

                if (isTimerType(varDecl)) {
                    String paramName = varDecl.getVariables().isEmpty()
                            ? null
                            : varDecl.getVariables().get(0).getSimpleName();

                    boolean usedInBody = paramName != null
                            && method.getBody() != null
                            && isIdentifierUsedInBody(method.getBody(), paramName);

                    return new TimerAnalysis(true, paramName, usedInBody);
                }
            }
        }

        return new TimerAnalysis(false, null, false);
    }

    /**
     * Checks if a variable declaration is of type Timer.
     */
    public static boolean isTimerType(J.VariableDeclarations varDecl) {
        JavaType paramType = varDecl.getType();

        if (paramType != null && TypeUtils.isOfClassType(paramType, TIMER_FQN)) {
            return true;
        }

        // Fallback: check by simple type name
        if (varDecl.getTypeExpression() != null) {
            String typeName = varDecl.getTypeExpression().toString();
            return "Timer".equals(typeName) || typeName.endsWith(".Timer");
        }

        return false;
    }

    /**
     * Checks if an identifier is used in a block body.
     */
    public static boolean isIdentifierUsedInBody(J.Block body, String identifierName) {
        if (body == null || identifierName == null) {
            return false;
        }

        AtomicBoolean found = new AtomicBoolean(false);
        new org.openrewrite.java.JavaIsoVisitor<AtomicBoolean>() {
            @Override
            public J.Identifier visitIdentifier(J.Identifier identifier, AtomicBoolean foundFlag) {
                if (identifier.getSimpleName().equals(identifierName)) {
                    foundFlag.set(true);
                }
                return super.visitIdentifier(identifier, foundFlag);
            }
        }.visit(body, found);

        return found.get();
    }

    // ========== Value Extraction ==========

    /**
     * Extracts a String value from an Expression (returns null for non-literals).
     */
    public static String extractStringValue(Expression expr) {
        if (expr instanceof J.Literal) {
            Object value = ((J.Literal) expr).getValue();
            return value != null ? value.toString() : null;
        }
        return null;
    }

    /**
     * Extracts a Boolean value from an Expression (returns null for non-literals).
     */
    public static Boolean extractBooleanValue(Expression expr) {
        if (expr instanceof J.Literal) {
            Object value = ((J.Literal) expr).getValue();
            if (value instanceof Boolean) {
                return (Boolean) value;
            }
        }
        return null;
    }

    // ========== Convenience Methods for Method Analysis ==========

    /**
     * Checks if a method has any @Schedule or @Schedules annotation.
     */
    public static boolean hasScheduleAnnotation(J.MethodDeclaration method) {
        for (J.Annotation ann : method.getLeadingAnnotations()) {
            if (isScheduleAnnotation(ann) || isSchedulesAnnotation(ann)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Extracts all ScheduleConfig from a method's annotations.
     * Returns configs from both @Schedule and @Schedules annotations.
     */
    public static List<ScheduleConfig> extractAllScheduleConfigs(J.MethodDeclaration method) {
        List<ScheduleConfig> configs = new ArrayList<>();

        for (J.Annotation ann : method.getLeadingAnnotations()) {
            if (isScheduleAnnotation(ann)) {
                configs.add(extractScheduleConfig(ann));
            } else if (isSchedulesAnnotation(ann)) {
                for (J.Annotation inner : extractSchedulesFromContainer(ann)) {
                    configs.add(extractScheduleConfig(inner));
                }
            }
        }

        return configs;
    }

    /**
     * Checks if all schedules on a method are safe for @Scheduled migration.
     */
    public static boolean allSchedulesSafeForMigration(J.MethodDeclaration method) {
        List<ScheduleConfig> configs = extractAllScheduleConfigs(method);

        if (configs.isEmpty()) {
            return false;
        }

        for (ScheduleConfig config : configs) {
            if (!config.isSafeForScheduledMigration()) {
                return false;
            }
        }

        return true;
    }
}
