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
    private final SchemaVerifier schemaVerifier;

    public SchemaGuard(Flyway flyway, SchemaVerifier schemaVerifier) {
        this.flyway = flyway;
        this.schemaVerifier = schemaVerifier;
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
        // Flyway 校验和只能发现迁移脚本层面的变化；约束可能在运行期被人直接删掉/改掉，
        // 因此再读取实际库元数据逐项核对一遍。
        schemaVerifier.verify();
        log.info("schema validation passed, {} migrations applied",
                flyway.info().applied().length);
    }
}
