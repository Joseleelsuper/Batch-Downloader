package es.ubu.batchdownloader.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

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
}
