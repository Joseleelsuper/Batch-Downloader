package es.ubu.batchdownloader.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Verifica los alias de arquitectura sobre fuentes reales sin duplicar aplicaciones. */
@Testcontainers(disabledWithoutDocker = true)
class CatalogArchitectureMySqlTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.13");
    static JdbcTemplate jdbc;

    @BeforeAll static void schema() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        jdbc.execute("CREATE TABLE software_apps(id INT PRIMARY KEY,name VARCHAR(80),catalog_status VARCHAR(16),operating_systems_json JSON)");
        jdbc.execute("CREATE TABLE download_sources(software_app_id INT,architecture VARCHAR(32))");
        jdbc.update("INSERT INTO software_apps VALUES(1,'Editor64','available',JSON_ARRAY('windows')),(2,'Editor32','available',JSON_ARRAY('windows')),(3,'EditorArm','available',JSON_ARRAY('linux')),(4,'Universal','available',JSON_ARRAY('linux'))");
        jdbc.update("INSERT INTO download_sources VALUES(1,'x86_64'),(1,'amd64'),(2,'i686'),(3,'aarch64'),(4,'neutral')");
    }

    private List<String> search(String architecture, List<String> systems) {
        var query = new CatalogQuery(null, "available", systems, architecture, List.of(), List.of());
        var sql = new StringBuilder("SELECT a.name FROM software_apps a WHERE 1=1");
        var params = new ArrayList<Object>();
        CatalogFilterSql.appendStructured(sql, params, query);
        return jdbc.queryForList(sql + " ORDER BY a.name", String.class, params.toArray());
    }

    @ParameterizedTest
    @CsvSource({"x64,Editor64", "x86_64,Editor64", "amd64,Editor64", "x86,Editor32",
            "i386,Editor32", "i686,Editor32", "arm64,EditorArm", "aarch64,EditorArm", "neutral,Universal"})
    void aliasesMatchPersistedArchitectures(String architecture, String expected) {
        assertThat(search(architecture, List.of())).containsExactly(expected);
    }

    @Test void architectureRespectsPlatformAndCanBeCleared() {
        assertThat(search("arm64", List.of("windows"))).isEmpty();
        assertThat(search("arm64", List.of("linux"))).containsExactly("EditorArm");
        assertThat(search(null, List.of())).containsExactly("Editor32", "Editor64", "EditorArm", "Universal");
        assertThat(search("x64' OR 1=1 --", List.of())).isEmpty();
    }
}
