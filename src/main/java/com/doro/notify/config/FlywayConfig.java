package com.doro.notify.config;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/** 마이그레이션을 직접 돌린다(doro-interview 와 같은 방식). 다른 빈이 이 빈에 의존해 스키마가 준비된 뒤에 실행된다. */
@Configuration
public class FlywayConfig {

    private static final Logger log = LoggerFactory.getLogger(FlywayConfig.class);

    @Bean
    public Flyway flyway(
            DataSource dataSource,
            @Value("${spring.flyway.locations:classpath:db/migration}") String locations,
            @Value("${spring.flyway.table:notify_schema_history}") String table,
            @Value("${spring.flyway.baseline-on-migrate:true}") boolean baselineOnMigrate) {
        log.info("Flyway migration: locations={}, table={}", locations, table);
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations(locations)
                .table(table)
                .baselineOnMigrate(baselineOnMigrate)
                .baselineVersion("0")
                .load();
        flyway.migrate();
        return flyway;
    }
}
