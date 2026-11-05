package es.ubu.batchdownloader.softwarerequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import es.ubu.batchdownloader.common.ConflictException;
import es.ubu.batchdownloader.common.NotFoundException;
import es.ubu.batchdownloader.common.UnauthorizedException;
import es.ubu.batchdownloader.softwarerequest.SoftwareRequestDtos.ReviewSoftwareRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Valida persistencia, aislamiento por cuenta y duplicados concurrentes con el esquema real. */
@Testcontainers(disabledWithoutDocker = true)
class SoftwareRequestMySqlTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.13");
    static final UUID OWNER = UUID.randomUUID(), OTHER = UUID.randomUUID(), ADMIN = UUID.randomUUID();
    static JdbcTemplate jdbc;
    static SoftwareRequestRepository requests;
    static TransactionTemplate transaction;

    @BeforeAll static void schema() {
        var source = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE core_users(id CHAR(36) PRIMARY KEY,role VARCHAR(16),enabled BOOLEAN)");
        jdbc.execute("CREATE TABLE software_apps(id INT PRIMARY KEY,name VARCHAR(160),official_url VARCHAR(2048),app_status VARCHAR(16))");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V26__software_inclusion_requests.sql")).execute(source);
        requests = new SoftwareRequestRepository(jdbc, Clock.fixed(Instant.parse("2026-11-05T12:00:00Z"), ZoneOffset.UTC));
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
    }

    @BeforeEach void reset() {
        jdbc.update("DELETE FROM software_inclusion_requests");
        jdbc.update("DELETE FROM core_users");
        jdbc.update("DELETE FROM software_apps");
        jdbc.update("INSERT INTO core_users VALUES(?,'USER',TRUE),(?,'USER',TRUE),(?,'ADMIN',TRUE)",
                OWNER.toString(), OTHER.toString(), ADMIN.toString());
    }

    private SoftwareRequestDtos.SoftwareRequest create(UUID owner, String name, String url) {
        return transaction.execute(status -> requests.create(owner, name, url));
    }

    @Test void storesPendingRequestAndOnlyItsOwnerCanListIt() {
        var request = create(OWNER, " New Editor ", "https://EXAMPLE.org:443/#intro");
        assertThat(request.status()).isEqualTo("pending");
        assertThat(request.officialUrl()).isEqualTo("https://example.org/");
        assertThat(request.reviewedAt()).isNull();
        assertThat(request.createdAt()).isEqualTo(Instant.parse("2026-11-05T12:00:00Z"));
        assertThat(requests.list(OWNER, null, 1, 12).data()).containsExactly(request);
        assertThat(requests.list(OTHER, null, 1, 12).data()).isEmpty();
        assertThat(requests.list(null, "pending", 1, 12).total()).isEqualTo(1);
    }

    @Test void rejectsObviousPersonalAndCatalogueDuplicatesWithoutRevealingOtherAccounts() {
        create(OWNER, "New Editor", "https://example.org/");
        assertThatThrownBy(() -> create(OWNER, "NEW EDITOR", "https://other.org/"))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> create(OWNER, "Another name", "https://EXAMPLE.org:443/#different"))
                .isInstanceOf(ConflictException.class);
        assertThat(create(OTHER, "New Editor", "https://example.org/").status()).isEqualTo("pending");
        jdbc.update("INSERT INTO software_apps VALUES(1,'Available','https://available.org','active')");
        assertThatThrownBy(() -> create(OWNER, "available", "https://different.org"))
                .isInstanceOfSatisfying(ConflictException.class, error -> assertThat(error.code()).isEqualTo("software_already_catalogued"));
        assertThatThrownBy(() -> create(OWNER, "Alias", "https://available.org/"))
                .isInstanceOf(ConflictException.class);
        assertThat(requests.list(null, null, 1, 12).total()).isEqualTo(2);
    }

    @Test void concurrentDuplicateSubmissionPersistsOnlyOneRequest() throws Exception {
        Callable<String> submit = () -> {
            try { create(OWNER, "New Editor", "https://example.org/"); return "created"; }
            catch (ConflictException exception) { return exception.code(); }
        };
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var outcomes = pool.invokeAll(List.of(submit, submit));
            assertThat(List.of(outcomes.getFirst().get(), outcomes.getLast().get()))
                    .containsExactlyInAnyOrder("created", "software_request_duplicate");
        }
        assertThat(requests.list(OWNER, null, 1, 12).total()).isEqualTo(1);
    }

    @Test void reviewsAreVersionedAndDoNotPublishCatalogueApps() {
        var request = create(OWNER, "New Editor", "https://example.org");
        var accepted = requests.review(request.id(), ADMIN, new ReviewSoftwareRequest("accepted", "Revisión favorable", 0L));
        assertThat(accepted.version()).isEqualTo(1);
        assertThat(accepted.reviewedAt()).isNotNull();
        assertThat(accepted.reviewNote()).isEqualTo("Revisión favorable");
        assertThat(requests.list(OWNER, null, 1, 12).data().getFirst().status()).isEqualTo("accepted");
        assertThatThrownBy(() -> requests.review(request.id(), ADMIN, new ReviewSoftwareRequest("rejected", "Otra revisión", 0L)))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> requests.review(UUID.randomUUID(), ADMIN, new ReviewSoftwareRequest("accepted", "Motivo", 0L)))
                .isInstanceOf(NotFoundException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM software_apps", Integer.class)).isZero();
    }

    @Test void deletionCascadesAndDisabledAccountsCannotSubmit() {
        create(OWNER, "New Editor", "https://example.org");
        jdbc.update("UPDATE core_users SET enabled=FALSE WHERE id=?", OWNER.toString());
        assertThatThrownBy(() -> create(OWNER, "Other", "https://other.org"))
                .isInstanceOf(UnauthorizedException.class);
        jdbc.update("DELETE FROM core_users WHERE id=?", OWNER.toString());
        assertThat(requests.list(null, null, 1, 12).total()).isZero();
    }

    @Test void ownerLimitIsEnforcedAndPagingIsBounded() {
        for (int i = 0; i < 50; i++) create(OWNER, "App " + i, "https://example.org/" + i);
        assertThatThrownBy(() -> create(OWNER, "Excess", "https://example.org/excess"))
                .isInstanceOfSatisfying(ConflictException.class, error -> assertThat(error.code()).isEqualTo("software_request_limit"));
        var page = requests.list(OWNER, null, -1, 200);
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.pageSize()).isEqualTo(50);
        assertThat(page.data()).hasSize(50);
    }
}
