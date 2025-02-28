package choral.reactive.tracing;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.logs.LogRecordBuilder;
import io.opentelemetry.api.logs.Severity;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;

public class Logger implements io.opentelemetry.api.logs.Logger {
    private final io.opentelemetry.api.logs.Logger logger;
    private final String className;

    public Logger(OpenTelemetry openTelemetry, String className) {
        this.logger = openTelemetry.getLogsBridge().get(JaegerConfiguration.TRACER_NAME);
        this.className = className;
    }

    @Override
    public LogRecordBuilder logRecordBuilder() {
        return logger.logRecordBuilder()
            .setAllAttributes(
                Attributes.builder()
                    .put("class_name", className)
                    .build()
            );
    }

    public void log(Severity level, String message, Attributes attributes) {
        logger.logRecordBuilder()
            .setAllAttributes(
                Attributes.builder()
                    .put("class_name", className)
                    .putAll(attributes)
                    .build()
            )
            .setBody(message)
            .setSeverity(level)
            .emit();
    }

    public void log(Severity level, String message) {
        log(level, message, Attributes.empty());
    }

    public void debug(String message, Attributes attributes) {
        log(Severity.DEBUG, message, attributes);
    }

    public void debug(String message) {
        log(Severity.DEBUG, message, Attributes.empty());
    }

    public void info(String message, Attributes attributes) {
        log(Severity.INFO, message, attributes);
    }

    public void info(String message) {
        log(Severity.INFO, message, Attributes.empty());
    }

    public void warn(String message, Attributes attributes) {
        log(Severity.WARN, message, attributes);
    }

    public void warn(String message) {
        log(Severity.WARN, message, Attributes.empty());
    }

    public void error(String message, Attributes attributes) {
        log(Severity.ERROR, message, attributes);
    }

    public void error(String message) {
        log(Severity.ERROR, message, Attributes.empty());
    }

    public void exception(String message, Throwable exception) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        exception.printStackTrace(pw);
        String stackTrace = sw.toString();

        error(
            message,
            Attributes.builder()
                .put("exception.message", exception.getMessage())
                .put("exception.stacktrace", stackTrace)
                .put("exception.type", exception.getClass().getSimpleName())
                .build()
        );
    }
}
