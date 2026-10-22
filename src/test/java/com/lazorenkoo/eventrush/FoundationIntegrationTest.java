package com.lazorenkoo.eventrush;

import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:17.11:///eventrush_test",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.datasource.username=test",
        "spring.datasource.password=test"
})
class FoundationIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void jpaAndJdbcUseTheDatabaseInitializedByFlyway() {
        String database = jdbc.queryForObject("select current_database()", String.class);
        try (var entityManager = entityManagerFactory.createEntityManager()) {
            assertThat(entityManager.createNativeQuery("select current_database()").getSingleResult())
                    .isEqualTo(database);
        }
        assertThat(jdbc.queryForObject("select to_regclass('public.flyway_schema_history')::text", String.class))
                .isEqualTo("flyway_schema_history");
        assertThat(jdbc.queryForObject("select version()", String.class)).startsWith("PostgreSQL 17.");
    }
}
