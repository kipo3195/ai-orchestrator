package com.ucware.ai_orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.sql.init.SqlInitializationAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

class DatabaseInitializationTests {

    @ParameterizedTest
    @ValueSource(strings = {"local", "prod"})
    void applicationStartupDoesNotExecuteBundledSchema(String profile) {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DataSourceAutoConfiguration.class, SqlInitializationAutoConfiguration.class))
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues(
                        "spring.profiles.active=" + profile,
                        "spring.config.import=classpath:config/result-router.properties",
                        "spring.datasource.url=jdbc:h2:mem:no-init-" + profile,
                        "spring.datasource.username=sa",
                        "spring.datasource.password=",
                        "spring.sql.init.schema-locations=classpath:db/h2/schema.sql")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getEnvironment().getProperty("spring.sql.init.mode"))
                            .isEqualTo("never");
                    JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
                    assertThat(jdbc.queryForObject("""
                            SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES
                            WHERE TABLE_NAME IN ('AI_GLOBAL_POLICY', 'RETURNED_MESSAGE_REQUEST')
                            """, Integer.class)).isZero();
                });
    }
}
