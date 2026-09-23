package es.ubu.batchdownloader.identity.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import es.ubu.batchdownloader.identity.domain.PendingMagicLink;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest(properties = {
    "spring.flyway.enabled=false",
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SpringDataPendingMagicLinkRepositoryTest {
    @Autowired
    private SpringDataPendingMagicLinkRepository repository;

    @Test
    void deletesExpiredRequestsWithoutACallerTransaction() {
        Instant now = Instant.parse("2026-09-21T16:31:22Z");
        PendingMagicLink expired = PendingMagicLink.issue(
                "user@example.com",
                "user@example.com",
                "expired-token-hash",
                "es",
                now.minusSeconds(1),
                now.minusSeconds(901));
        repository.saveAndFlush(PendingMagicLinkEntity.from(expired));

        assertThat(repository.deleteExpiredOrConsumed(now)).isEqualTo(1);
        assertThat(repository.count()).isZero();
    }
}
