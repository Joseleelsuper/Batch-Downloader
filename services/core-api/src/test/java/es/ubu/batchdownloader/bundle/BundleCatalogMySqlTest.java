package es.ubu.batchdownloader.bundle;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

import com.fasterxml.jackson.databind.ObjectMapper;
import es.ubu.batchdownloader.catalog.CatalogRepository;
import es.ubu.batchdownloader.catalog.SemanticSearchClient;
import es.ubu.batchdownloader.catalog.SemanticCandidateSet;
import es.ubu.batchdownloader.common.NotFoundException;
import es.ubu.batchdownloader.common.UnauthorizedException;
import es.ubu.batchdownloader.common.UuidBytes;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Comprueba votos, consultas y señal semántica con el motor MySQL real. */
@Testcontainers(disabledWithoutDocker = true)
class BundleCatalogMySqlTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.13")
            .withCommand("--log-bin-trust-function-creators=1");
    static JdbcTemplate jdbc;
    static TransactionTemplate tx;
    BundleActivityRepository activity;

    @BeforeAll static void schema() {
        var ds = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        for (String table : List.of(
                "core_users(id CHAR(36) PRIMARY KEY,username VARCHAR(80),role VARCHAR(16),enabled BOOLEAN)",
                "bundles(id BINARY(16) PRIMARY KEY,slug VARCHAR(180) UNIQUE,name VARCHAR(180),description TEXT,type VARCHAR(32),visibility VARCHAR(32),owner_id CHAR(36),star_count INT DEFAULT 0,app_count INT DEFAULT 0,updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,version BIGINT DEFAULT 0)",
                "bundle_stars(id BINARY(16) PRIMARY KEY,bundle_id BINARY(16),user_key VARCHAR(180),created_at DATETIME,UNIQUE(bundle_id,user_key),FOREIGN KEY(bundle_id) REFERENCES bundles(id) ON DELETE CASCADE)",
                "bundle_tags(bundle_id BINARY(16),tag VARCHAR(120),normalized_tag VARCHAR(120))",
                "download_job_receipts(job_id CHAR(36) PRIMARY KEY,saved_bytes BIGINT)",
                "software_apps(id BINARY(16) PRIMARY KEY,app_status VARCHAR(32),catalog_status VARCHAR(32))",
                "bundle_items(bundle_id BINARY(16),software_app_id BINARY(16),sort_order INT)",
                "download_sources(id BINARY(16),software_app_id BINARY(16),operating_system VARCHAR(32),resolution_status VARCHAR(32),validation_status VARCHAR(32),catalog_available BOOLEAN)",
                "resolved_sources(download_source_id BINARY(16),catalog_downloadable BOOLEAN)")) jdbc.execute("CREATE TABLE "+table);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V24__bundle_catalog_activity.sql")).execute(ds);
    }

    @BeforeEach void reset() {
        for (String table : List.of("bundle_stars","bundle_tags","download_job_receipts","bundle_items","bundles","core_users")) jdbc.update("DELETE FROM "+table);
        activity = new BundleActivityRepository(jdbc);
    }

    UUID user(String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO core_users VALUES(?,?,?,TRUE)", id.toString(), role+id, role);
        return id;
    }

    UUID bundle(String name, String type, String visibility, UUID owner) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO bundles(id,slug,name,description,type,visibility,owner_id) VALUES(?,?,?,?,?,?,?)",
                UuidBytes.fromUuid(id), id.toString(), name, "Descripción "+name, type, visibility, owner.toString());
        return id;
    }

    @Test void concurrentRetriesAreOneVoteAndAdminBadgeFollowsTheLastAdminVote() throws Exception {
        UUID regular = user("USER"), admin = user("ADMIN");
        UUID id = bundle("Dev", "user", "public", regular);
        try (var executor = Executors.newFixedThreadPool(6)) {
            var tasks = java.util.stream.IntStream.range(0,12).<java.util.concurrent.Callable<Object>>mapToObj(i ->
                    () -> tx.execute(status -> activity.setStar(id.toString(), regular, true))).toList();
            for (var future : executor.invokeAll(tasks)) future.get();
        }
        assertThat(activity.states(List.of(id.toString()), regular).get(id.toString()).starCount()).isEqualTo(1);
        assertThat(tx.execute(status -> activity.setStar(id.toString(), admin, true)).hasAdminStar()).isTrue();
        var result = tx.execute(status -> activity.setStar(id.toString(), admin, false));
        assertThat(result.hasAdminStar()).isFalse();
        assertThat(result.starCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT version FROM bundles WHERE id=?", Long.class, UuidBytes.fromUuid(id))).isZero();
    }

    @Test void privateBundlesKeepTheirVotesHiddenAndCannotBeVotedEvenByTheirOwner() {
        UUID owner = user("USER");
        UUID id = bundle("Privado", "user", "public", owner);
        tx.execute(status -> activity.setStar(id.toString(), owner, true));
        jdbc.update("UPDATE bundles SET visibility='private' WHERE id=?", UuidBytes.fromUuid(id));
        assertThat(activity.states(List.of(id.toString()), owner).get(id.toString())).isEqualTo(BundleDtos.BundleStarState.HIDDEN);
        assertThatThrownBy(() -> tx.execute(status -> activity.setStar(id.toString(), owner, false))).isInstanceOf(NotFoundException.class);
        jdbc.update("UPDATE bundles SET visibility='public' WHERE id=?", UuidBytes.fromUuid(id));
        assertThat(activity.states(List.of(id.toString()), owner).get(id.toString()).starred()).isTrue();
        jdbc.update("UPDATE core_users SET enabled=FALSE WHERE id=?", owner.toString());
        assertThatThrownBy(() -> tx.execute(status -> activity.setStar(id.toString(), owner, true))).isInstanceOf(UnauthorizedException.class);
    }

    @Test void filtersFacetsPaginationAndSemanticSourceSharePublicBundleMetadata() {
        UUID owner = user("USER");
        UUID official = bundle("Alpha", "official", "official", owner);
        UUID community = bundle("Beta", "user", "public", owner);
        bundle("Invisible", "user", "private", owner);
        jdbc.update("INSERT INTO bundle_tags VALUES(?, 'Trabajo', 'trabajo')", UuidBytes.fromUuid(official));
        SemanticSearchClient semantic = mock(SemanticSearchClient.class);
        when(semantic.resolveBundles(any(), any())).thenReturn(SemanticCandidateSet.lexical());
        var repository = new BundleCatalogRepository(jdbc, new BundleReadRepository(jdbc,mock(CatalogRepository.class)),activity,semantic);
        var filters = new BundleCatalogRepository.Filters(null,null,List.of(),null,List.of(),"lexical");
        var first = tx.execute(s -> repository.search(filters,"name",1,1,owner));
        assertThat(first.total()).isEqualTo(2);
        assertThat(first.data()).extracting(BundleDtos.BundleSummary::id).containsExactly(official.toString());
        assertThat(first.counts()).containsEntry("all",2L).containsEntry("official",1L).containsEntry("public",1L);
        assertThat(repository.search(filters,"name",2,1,owner).data()).extracting(BundleDtos.BundleSummary::id).containsExactly(community.toString());
        assertThat(repository.facets(filters).tags()).extracting(BundleDtos.BundleFacet::value).containsExactly("trabajo");
        var source = new BundleSemanticController(jdbc,new ObjectMapper(),"test-token");
        assertThat((List<?>) source.documents("test-token",null,500).get("documents")).hasSize(2);
        long revision = source.status("test-token").get("revision");
        tx.execute(s -> activity.setStar(official.toString(),owner,true));
        jdbc.update("UPDATE bundles SET download_count=download_count+1 WHERE id=?",UuidBytes.fromUuid(official));
        assertThat(source.status("test-token").get("revision")).isEqualTo(revision);
        jdbc.update("UPDATE bundles SET description='Nueva descripción' WHERE id=?",UuidBytes.fromUuid(official));
        assertThat(source.status("test-token").get("revision")).isGreaterThan(revision);
        assertThatThrownBy(() -> source.status("wrong-token")).isInstanceOf(UnauthorizedException.class);
    }

    @Test void combinesOwnTagsCreatorAndSelectablePlatformsAndExcludesPrivateSemanticCandidates() {
        UUID firstOwner=user("USER"), otherOwner=user("USER");
        UUID alpha=bundle("Álpha herramientas","user","public",firstOwner);
        UUID beta=bundle("Beta ocio","user","public",otherOwner);
        UUID gamma=bundle("Gamma herramientas","user","public",otherOwner);
        UUID hidden=bundle("Oculto","user","private",firstOwner);
        for (UUID id : List.of(alpha,gamma)) jdbc.update("INSERT INTO bundle_tags VALUES(?,'Trabajo','trabajo')",UuidBytes.fromUuid(id));
        jdbc.update("INSERT INTO bundle_tags VALUES(?,'Dev','dev')",UuidBytes.fromUuid(alpha));
        addInstaller(alpha,"windows","available");
        addInstaller(gamma,"linux","available");
        addInstaller(beta,"windows","review");
        SemanticSearchClient semantic=mock(SemanticSearchClient.class);
        when(semantic.resolveBundles(any(),any())).thenReturn(SemanticCandidateSet.lexical());
        var repository=new BundleCatalogRepository(jdbc,new BundleReadRepository(jdbc,mock(CatalogRepository.class)),activity,semantic);
        var combined=new BundleCatalogRepository.Filters("public","trabajo",List.of("dev","trabajo"),firstOwner.toString(),List.of("windows","linux"),"lexical");
        assertThat(repository.search(combined,"updated",1,12,null).data()).extracting(BundleDtos.BundleSummary::id).containsExactly(alpha.toString());
        assertThat(repository.facets(combined).creators()).extracting(BundleDtos.BundleFacet::value).containsExactly(firstOwner.toString());
        var platforms=new BundleCatalogRepository.Filters("public",null,List.of(),null,List.of("windows","linux"),"lexical");
        assertThat(repository.search(platforms,"name",1,12,null).data()).extracting(BundleDtos.BundleSummary::id).containsExactly(alpha.toString(),gamma.toString());
        assertThat(repository.search(platforms,"name",1,1,null).alphabet()).extracting(BundleDtos.BundleAlphabet::letter).containsExactly("A","G");
        jdbc.update("UPDATE bundles SET download_count=10 WHERE id=?",UuidBytes.fromUuid(gamma));
        assertThat(repository.search(platforms,"downloads",1,12,null).data().getFirst().id()).isEqualTo(gamma.toString());
        tx.execute(status -> activity.setStar(alpha.toString(),firstOwner,true));
        assertThat(repository.search(platforms,"stars",1,12,null).data().getFirst().id()).isEqualTo(alpha.toString());
        String candidates="[{\"appId\":\""+hidden+"\",\"rank\":1,\"similarity\":0.9},{\"appId\":\""+alpha+"\",\"rank\":2,\"similarity\":0.8}]";
        when(semantic.resolveBundles(any(),any())).thenReturn(new SemanticCandidateSet(
                es.ubu.batchdownloader.catalog.CatalogSearchMode.SEMANTIC,es.ubu.batchdownloader.catalog.CatalogSearchMode.SEMANTIC,candidates,"model","index",null));
        var semanticFilters=new BundleCatalogRepository.Filters(null,"no lexical match",List.of(),null,List.of(),"semantic");
        var result=repository.search(semanticFilters,"relevance",1,12,null);
        assertThat(result.data()).extracting(BundleDtos.BundleSummary::id).containsExactly(alpha.toString());
        assertThat(result.total()).isEqualTo(1);
    }

    void addInstaller(UUID bundle,String os,String status) {
        UUID app=UUID.randomUUID(),source=UUID.randomUUID();
        jdbc.update("INSERT INTO software_apps VALUES(?,'active',?)",UuidBytes.fromUuid(app),status);
        jdbc.update("INSERT INTO bundle_items VALUES(?,?,0)",UuidBytes.fromUuid(bundle),UuidBytes.fromUuid(app));
        jdbc.update("INSERT INTO download_sources VALUES(?,?,?,'direct','valid',TRUE)",UuidBytes.fromUuid(source),UuidBytes.fromUuid(app),os);
        jdbc.update("INSERT INTO resolved_sources VALUES(?,TRUE)",UuidBytes.fromUuid(source));
    }
}
