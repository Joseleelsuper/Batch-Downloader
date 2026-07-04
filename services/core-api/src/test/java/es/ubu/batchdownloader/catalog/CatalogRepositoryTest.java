package es.ubu.batchdownloader.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CatalogRepositoryTest {
    @Test
    void facetLetterGroupsLatinLettersAndNonLatinPrefixes() {
        assertThat(CatalogRepository.facetLetter(".NET")).isEqualTo("N");
        assertThat(CatalogRepository.facetLetter("Álvaro Tools")).isEqualTo("A");
        assertThat(CatalogRepository.facetLetter("4t Niagara Software")).isEqualTo("#");
        assertThat(CatalogRepository.facetLetter("東Vendor")).isEqualTo("#");
    }

    @Test
    void requiredTagMatchesDefaultsToAllAndClampsExplicitValues() {
        assertThat(CatalogRepository.requiredTagMatches(3, null, "all")).isEqualTo(3);
        assertThat(CatalogRepository.requiredTagMatches(3, null, "any")).isEqualTo(1);
        assertThat(CatalogRepository.requiredTagMatches(3, 9, "all")).isEqualTo(3);
        assertThat(CatalogRepository.requiredTagMatches(3, 0, "all")).isEqualTo(1);
    }
}
