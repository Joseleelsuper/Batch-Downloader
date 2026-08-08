package es.ubu.batchdownloader.bundle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import es.ubu.batchdownloader.bundle.BundleDtos.BundleDetails;
import es.ubu.batchdownloader.bundle.BundleDtos.CreateOwnBundleRequest;
import es.ubu.batchdownloader.bundle.BundleDtos.UpdateOwnBundleRequest;
import es.ubu.batchdownloader.catalog.CatalogRepository;
import es.ubu.batchdownloader.common.ConflictException;
import es.ubu.batchdownloader.common.NotFoundException;
import es.ubu.batchdownloader.common.UuidBytes;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class UserBundleRepositoryTest {
    @Test
    void createsPrivateUserBundlesWithTheUuidOwnerAndDeduplicatedPublicApps() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CatalogRepository catalog = mock(CatalogRepository.class);
        BundleRepository publicBundles = mock(BundleRepository.class);
        UUID ownerId = UUID.randomUUID();
        UUID appId = UUID.randomUUID();
        UUID storedBundleId = UUID.randomUUID();
        when(catalog.publicSoftwareAppId("public-app")).thenReturn(appId);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        stubOwnedRow(jdbc, storedBundleId, 0);
        when(publicBundles.detailsInternal(anyString())).thenReturn(details(storedBundleId));
        UserBundleRepository repository = new UserBundleRepository(jdbc, catalog, publicBundles);

        var result = repository.create(
                ownerId,
                "person",
                new CreateOwnBundleRequest(
                        "My tools", "Useful tools", "my-tools", List.of("Tools", "tools"),
                        List.of("public-app", "public-app")));

        assertThat(result.visibility()).isEqualTo("private");
        verify(catalog, times(1)).publicSoftwareAppId("public-app");
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> parameters = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, org.mockito.Mockito.atLeastOnce()).update(sql.capture(), parameters.capture());
        int insert = java.util.stream.IntStream.range(0, sql.getAllValues().size())
                .filter(index -> sql.getAllValues().get(index).contains("INSERT INTO bundles"))
                .findFirst().orElseThrow();
        assertThat(sql.getAllValues().get(insert))
                .contains("'user', 'private'")
                .doesNotContain("owner_id = NULL");
        assertThat(parameters.getAllValues().get(insert)).contains(ownerId.toString(), "person");
    }

    @Test
    void reportsAnOptimisticConflictWithoutReplacingItems() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID ownerId = UUID.randomUUID();
        UUID bundleId = UUID.randomUUID();
        stubOwnedRow(jdbc, bundleId, 4);
        when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(invocation ->
                ((String) invocation.getArgument(0)).contains("UPDATE bundles") ? 0 : 1);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
        UserBundleRepository repository = new UserBundleRepository(
                jdbc, mock(CatalogRepository.class), mock(BundleRepository.class));

        assertThatThrownBy(() -> repository.update(
                ownerId,
                bundleId.toString(),
                new UpdateOwnBundleRequest(
                        "Changed", "Description", "my-tools", "public", List.of(), List.of(), 3L)))
                .isInstanceOfSatisfying(ConflictException.class,
                        exception -> assertThat(exception.code()).isEqualTo("bundle_conflict"));

        verify(jdbc, org.mockito.Mockito.never()).update(
                org.mockito.ArgumentMatchers.startsWith("DELETE FROM bundle_items"),
                any(Object[].class));
    }

    @Test
    void hidesBundlesThatAreNotOwnedByTheUuid() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        UserBundleRepository repository = new UserBundleRepository(
                jdbc, mock(CatalogRepository.class), mock(BundleRepository.class));

        assertThatThrownBy(() -> repository.details(UUID.randomUUID(), "someone-elses-bundle"))
                .isInstanceOfSatisfying(NotFoundException.class,
                        exception -> assertThat(exception.code()).isEqualTo("bundle_not_found"));
    }

    private static void stubOwnedRow(JdbcTemplate jdbc, UUID bundleId, long version) throws Exception {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    if (!sql.contains("FROM bundles")) return List.of();
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet row = mock(ResultSet.class);
                    when(row.getBytes("id")).thenReturn(UuidBytes.fromUuid(bundleId));
                    when(row.getString("slug")).thenReturn("my-tools");
                    when(row.getString("name")).thenReturn("My tools");
                    when(row.getString("description")).thenReturn("Useful tools");
                    when(row.getString("visibility")).thenReturn("private");
                    when(row.getInt("app_count")).thenReturn(1);
                    when(row.getTimestamp("updated_at"))
                            .thenReturn(Timestamp.valueOf(LocalDateTime.of(2026, 8, 8, 10, 0)));
                    when(row.getLong("version")).thenReturn(version);
                    return List.of(mapper.mapRow(row, 0));
                });
    }

    private static BundleDetails details(UUID id) {
        return new BundleDetails(
                id.toString(), "my-tools", "My tools", "Useful tools", "user", "private",
                0, 1, List.of("windows"), List.of(), List.of("Tools"), List.of(),
                LocalDateTime.of(2026, 8, 8, 10, 0));
    }
}
