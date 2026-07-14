package es.ubu.batchdownloader.downloads.application.port;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface CatalogSourceLookup {
    record VerifiedSource(UUID appId, UUID sourceRef, String operatingSystem, String architecture) {}

    Map<UUID, VerifiedSource> findVerifiedSources(Collection<UUID> appIds, List<String> operatingSystems);

    default Optional<VerifiedSource> findVerifiedSource(UUID appId, List<String> operatingSystems) {
        return Optional.ofNullable(findVerifiedSources(List.of(appId), operatingSystems).get(appId));
    }
}
