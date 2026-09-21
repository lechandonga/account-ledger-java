package com.github.lechandonga.accountledgerjava.db;

import com.github.lechandonga.accountledgerjava.error.SchemaMismatchException;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.ValidateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时显式校验数据库结构：迁移校验失败（校验和不符、缺失迁移、
 * 实际结构与预期不一致）时抛出 SchemaMismatchException 让应用启动失败，
 * 而不是静默忽略继续运行在不一致的结构上。
 */
@Component
public class SchemaGuard implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaGuard.class);

    private final Flyway flyway;

    public SchemaGuard(Flyway flyway) {
        this.flyway = flyway;
    }

    @Override
    public void run(ApplicationArguments args) {
        ValidateResult result = flyway.validateWithResult();
        if (!result.validationSuccessful) {
            log.error("schema validation failed: {}", result.errorDetails);
            throw new SchemaMismatchException(
                    "database schema does not match expected migrations: "
                            + (result.errorDetails != null ? result.errorDetails.errorMessage : "unknown"),
                    null);
        }
        log.info("schema validation passed, {} migrations applied",
                flyway.info().applied().length);
    }
}
