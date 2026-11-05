package es.ubu.batchdownloader.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import es.ubu.batchdownloader.common.BadRequestException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Ejecuta la combinación real de SO, arquitectura y mínimo de etiquetas en MySQL. */
@Testcontainers(disabledWithoutDocker = true)
class CatalogTagMinimumMySqlTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.13");
    static JdbcTemplate jdbc;

    @BeforeAll static void schema() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        jdbc.execute("CREATE TABLE software_apps(id INT PRIMARY KEY,name VARCHAR(80),app_status VARCHAR(16),catalog_status VARCHAR(16),operating_systems_json JSON)");
        jdbc.execute("CREATE TABLE software_app_tags(software_app_id INT,normalized_tag VARCHAR(80))");
        jdbc.execute("CREATE TABLE download_sources(software_app_id INT,architecture VARCHAR(32))");
        jdbc.update("INSERT INTO software_apps VALUES(1,'Editor','active','available',JSON_ARRAY('windows')),(2,'Player','active','available',JSON_ARRAY('windows')),(3,'Inactive','inactive','available',JSON_ARRAY('windows'))");
        jdbc.update("INSERT INTO software_app_tags VALUES(1,'video'),(1,'tools'),(2,'video'),(3,'video'),(3,'tools')");
        jdbc.update("INSERT INTO download_sources VALUES(1,'x86_64'),(2,'x86_64'),(3,'x86_64')");
    }

    private List<String> search(String os, String architecture, Integer minimum) {
        var query = new CatalogQuery(null, "available", List.of(os), architecture,
                List.of("video", "TOOLS", "tools"), List.of(), minimum);
        var sql = new StringBuilder("SELECT a.name FROM software_apps a WHERE a.app_status='active'");
        var params = new ArrayList<Object>();
        CatalogFilterSql.appendStructured(sql, params, query);
        return jdbc.queryForList(sql + " ORDER BY a.name", String.class, params.toArray());
    }

    @Test void allAndMinimumTagsCombineWithCompatibilityWithoutDuplicateCounting() {
        assertThat(search("windows", "x64", null)).containsExactly("Editor");
        assertThat(search("windows", "x64", 1)).containsExactly("Editor", "Player");
        assertThat(search("windows", "x86_64", 1)).containsExactly("Editor", "Player");
        assertThat(search("windows", "amd64", 1)).containsExactly("Editor", "Player");
        assertThat(search("windows", "x64", 2)).containsExactly("Editor");
        assertThat(search("linux", "x64", 1)).isEmpty();
        assertThat(search("windows", "arm64", 1)).isEmpty();
    }

    @Test void impossibleOrNonPositiveMinimumIsRejected() {
        for (int minimum : List.of(0, -1, 3)) {
            assertThatThrownBy(() -> search("windows", "x64", minimum)).isInstanceOf(BadRequestException.class);
        }
    }
}
