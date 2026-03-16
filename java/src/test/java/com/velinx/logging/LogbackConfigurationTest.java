package com.velinx.logging;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.status.Status;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class LogbackConfigurationTest {

    @Test
    void shouldLoadLogbackConfigurationWithoutErrors() throws Exception {
        URL configuration = Thread.currentThread().getContextClassLoader().getResource("logback-spring.xml");
        assertNotNull(configuration, "logback-spring.xml should be on the test classpath");

        LoggerContext context = new LoggerContext();
        context.putProperty("LOG_DIR", "target/test-logs");

        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        configurator.doConfigure(configuration);

        List<Status> statuses = context.getStatusManager().getCopyOfStatusList();
        boolean hasErrors = statuses.stream().anyMatch(status -> status.getLevel() == Status.ERROR);

        assertFalse(hasErrors, () -> "logback configuration contains errors: " + statuses);
    }
}
