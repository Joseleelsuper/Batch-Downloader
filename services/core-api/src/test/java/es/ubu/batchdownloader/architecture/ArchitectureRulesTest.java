package es.ubu.batchdownloader.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleName;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Protege los límites hexagonales de identidad y descargas frente a regresiones. */
@AnalyzeClasses(
        packages = "es.ubu.batchdownloader",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureRulesTest {
    /** El dominio solo conoce Java y los tipos de dominio de su propio contexto. */
    @ArchTest
    static final ArchRule DOMAIN_STAYS_FRAMEWORK_FREE = noClasses()
            .that().resideInAnyPackage("..downloads.domain..", "..identity.domain..")
            .should().dependOnClassesThat().resideOutsideOfPackages(
                    "java..", "..downloads.domain..", "..identity.domain..");

    /** Los casos de uso no pueden importar adaptadores de infraestructura. */
    @ArchTest
    static final ArchRule APPLICATION_DOES_NOT_DEPEND_ON_INFRASTRUCTURE = noClasses()
            .that().resideInAnyPackage("..downloads.application..", "..identity.application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..downloads.infrastructure..", "..identity.infrastructure..");

    /** El consumidor de eventos no puede admitir trabajos ni entregar sus archivos al usuario. */
    @ArchTest
    static final ArchRule EVENT_CONSUMER_USES_EVENT_APPLICATION = noClasses()
            .that().haveSimpleName("DownloadWorkerEventListener")
            .should().dependOnClassesThat(simpleName("DownloadJobService")
                    .or(simpleName("DownloadJobAccessService")));

    /** El planificador reutiliza trabajos admitidos sin crear nuevas selecciones. */
    @ArchTest
    static final ArchRule EXPIRATION_DOES_NOT_ADMIT_DOWNLOADS = noClasses()
            .that().haveSimpleName("DownloadStorageCoordinator")
            .should().dependOnClassesThat(simpleName("DownloadJobService")
                    .or(simpleName("CatalogSourceLookup")));
}
