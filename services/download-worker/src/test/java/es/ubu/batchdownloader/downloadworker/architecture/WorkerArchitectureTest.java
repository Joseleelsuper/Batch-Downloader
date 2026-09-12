package es.ubu.batchdownloader.downloadworker.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Protege el límite que permite a la aplicación depender de puertos y mantiene los adaptadores
 * concretos fuera de ella.
 *
 * @see es.ubu.batchdownloader.downloadworker.config.WorkerConfiguration
 * @see es.ubu.batchdownloader.downloadworker.ports.PublicUriPolicy
 * @since 0.1.0
 * @version 0.1.0
 * @category Pruebas de procesamiento y capacidad
 */
@AnalyzeClasses(packages = "es.ubu.batchdownloader.downloadworker", importOptions = ImportOption.DoNotIncludeTests.class)
class WorkerArchitectureTest {
    /**
     * Impide dependencias desde aplicación o puertos hacia adaptadores de infraestructura.
     */
    @ArchTest
    static final ArchRule APPLICATION_USES_PORTS = noClasses()
            .that().resideInAnyPackage("..application..", "..ports..")
            .should().dependOnClassesThat().resideInAPackage("..infrastructure..");
}
