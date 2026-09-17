package es.ubu.batchdownloader.downloadworker.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import es.ubu.batchdownloader.downloadworker.application.DownloadEventEmitter;
import es.ubu.batchdownloader.downloadworker.application.DownloadJobFiles;
import es.ubu.batchdownloader.downloadworker.application.ManualShortcutWriter;
import es.ubu.batchdownloader.downloadworker.application.DownloadManifestWriter;
import es.ubu.batchdownloader.downloadworker.application.LinuxInstallerBundleWriter;
import es.ubu.batchdownloader.downloadworker.application.DownloadResolutionService;
import es.ubu.batchdownloader.downloadworker.application.DownloadPipeline;
import es.ubu.batchdownloader.downloadworker.application.DownloadPipelineFactory;
import es.ubu.batchdownloader.downloadworker.application.DownloadCancellationRegistry;
import es.ubu.batchdownloader.downloadworker.application.DownloadWorkerMetrics;
import es.ubu.batchdownloader.downloadworker.application.FilenamePolicy;
import es.ubu.batchdownloader.downloadworker.ports.PublicUriPolicy;
import es.ubu.batchdownloader.downloadworker.application.DownloadJobHandler;
import es.ubu.batchdownloader.downloadworker.application.DownloadJobProcessor;
import es.ubu.batchdownloader.downloadworker.application.JobCapacity;
import es.ubu.batchdownloader.downloadworker.infrastructure.archive.ZipArchiveBuilder;
import es.ubu.batchdownloader.downloadworker.infrastructure.http.DnsHostResolver;
import es.ubu.batchdownloader.downloadworker.infrastructure.http.HostResolver;
import es.ubu.batchdownloader.downloadworker.infrastructure.http.JdkHttpsRemoteDownloader;
import es.ubu.batchdownloader.downloadworker.infrastructure.http.HostLimitedRemoteDownloader;
import es.ubu.batchdownloader.downloadworker.infrastructure.http.RetryingRemoteDownloader;
import es.ubu.batchdownloader.downloadworker.infrastructure.http.PartialFileCleanupRemoteDownloader;
import es.ubu.batchdownloader.downloadworker.infrastructure.http.MeteredRemoteDownloader;
import es.ubu.batchdownloader.downloadworker.infrastructure.http.PublicHttpsUriPolicy;
import es.ubu.batchdownloader.downloadworker.infrastructure.messaging.RabbitEventPublisher;
import es.ubu.batchdownloader.downloadworker.infrastructure.persistence.JdbcInboxRepository;
import es.ubu.batchdownloader.downloadworker.infrastructure.storage.MinioArtifactStore;
import es.ubu.batchdownloader.downloadworker.infrastructure.source.HttpJobItemMetadataLookup;
import es.ubu.batchdownloader.downloadworker.infrastructure.source.HttpSourceReferenceResolver;
import es.ubu.batchdownloader.downloadworker.messaging.InboxDownloadJobHandler;
import es.ubu.batchdownloader.downloadworker.messaging.ValidatedDownloadJobHandler;
import es.ubu.batchdownloader.downloadworker.ports.ArchiveBuilder;
import es.ubu.batchdownloader.downloadworker.ports.ArtifactStore;
import es.ubu.batchdownloader.downloadworker.ports.EventPublisher;
import es.ubu.batchdownloader.downloadworker.ports.InboxRepository;
import es.ubu.batchdownloader.downloadworker.ports.JobItemMetadataLookup;
import es.ubu.batchdownloader.downloadworker.ports.RemoteDownloader;
import es.ubu.batchdownloader.downloadworker.ports.SourceReferenceResolver;
import io.micrometer.core.instrument.MeterRegistry;
import io.minio.MinioClient;
import jakarta.validation.Validator;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Compone en Spring puertos, adaptadores y políticas de descarga y configura los colaboradores
 * compartidos que utiliza el procesador sin construir infraestructura desde la aplicación.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.downloadworker.application.DownloadJobProcessor
 * @see es.ubu.batchdownloader.downloadworker.application.DownloadPipelineFactory
 * @see es.ubu.batchdownloader.downloadworker.ports.RemoteDownloader
 * @since 0.1.0
 * @version 0.1.0
 * @category Configuración del worker
 */
@Configuration
@EnableConfigurationProperties({
    DownloadProperties.class,
    MessagingProperties.class,
    StorageProperties.class,
    SourceResolverProperties.class,
    CoreApiProperties.class
})
public class WorkerConfiguration {
    /**
     * Captura colaboradores compartidos y deja que cada apertura cree presupuesto, nombres y
     * ventana propios del trabajo.
     *
     * @param executor Pool global compartido por ventanas de resolución y descarga.
     * @param downloader Cadena compuesta de seguridad HTTP, integridad, reintentos y métricas.
     * @param filenames Política de nombres seguros y únicos por archivo.
     * @param properties Configuración tipada de destinos, límites o credenciales que utiliza el
     *     componente construido.
     * @param cancellations Registro común que conecta mensajes de cancelación con tareas en vuelo.
     * @param metrics Medidores del ciclo de vida de descarga y empaquetado.
     * @param events Emisor común de eventos deterministas de progreso y resultado.
     * @param clock Reloj UTC compartido para eventos, reservas y métricas.
     * @param files Ciclo de vida de temporales y compensación de objetos incompletos.
     * @return factoría de pipelines de descarga.
     */
    @Bean
    DownloadPipelineFactory downloadPipelines(ExecutorService executor, RemoteDownloader downloader,
            FilenamePolicy filenames, DownloadProperties properties, DownloadCancellationRegistry cancellations,
            DownloadWorkerMetrics metrics, DownloadEventEmitter events, Clock clock, DownloadJobFiles files) {
        DownloadPipeline.Dependencies dependencies = new DownloadPipeline.Dependencies(
                executor, downloader, filenames, properties, cancellations, metrics, events, clock, files);
        return (event, items, directory, window) -> new DownloadPipeline(
                event, items, directory, window, dependencies);
    }

    /**
     * Conecta el transporte de eventos con el reloj y vigencia de resultados.
     *
     * @param publisher Puerto de publicación de eventos confirmados por el broker.
     * @param storage Configuración de almacenamiento que aporta la vigencia de resultados.
     * @param clock Reloj UTC compartido para eventos, reservas y métricas.
     * @return emisor de transiciones con identidad determinista.
     */
    @Bean
    DownloadEventEmitter downloadEvents(EventPublisher publisher, StorageProperties storage, Clock clock) {
        return new DownloadEventEmitter(publisher, storage, clock);
    }

    /**
     * Conecta limpieza de temporales y compensación de objetos con sus métricas.
     *
     * @param store Puerto de almacenamiento y retirada de artefactos.
     * @param metrics Medidores del ciclo de vida de descarga y empaquetado.
     * @param properties Configuración tipada de destinos, límites o credenciales que utiliza el
     *     componente construido.
     * @return gestor del ciclo de vida de archivos por trabajo.
     */
    @Bean
    DownloadJobFiles downloadJobFiles(ArtifactStore store, DownloadWorkerMetrics metrics, DownloadProperties properties) {
        return new DownloadJobFiles(store, metrics, properties);
    }

    /**
     * Compone metadatos, nombres y validación pública para generar alternativas manuales.
     *
     * @param metadata Consulta de nombres y páginas oficiales de elementos fallidos.
     * @param filenames Política de nombres seguros y únicos por archivo.
     * @param uris Puerto que valida destinos públicos sin acoplar la aplicación al transporte
     *     concreto.
     * @return generador de accesos oficiales sin parámetros sensibles.
     */
    @Bean
    ManualShortcutWriter manualShortcuts(JobItemMetadataLookup metadata, FilenamePolicy filenames, PublicUriPolicy uris) {
        return new ManualShortcutWriter(metadata, filenames, uris);
    }

    /**
     * Conecta JSON y reloj del manifiesto entregable.
     *
     * @param mapper Serializador de manifiestos y configuración del instalador Linux.
     * @param clock Reloj UTC compartido para eventos, reservas y métricas.
     * @return generador que conserva el orden de selección original.
     */
    @Bean
    DownloadManifestWriter downloadManifests(ObjectMapper mapper, Clock clock) {
        return new DownloadManifestWriter(mapper, clock);
    }

    /**
     * Configura la serialización del runtime y recetas Linux offline.
     *
     * @param mapper Serializador de manifiestos y configuración del instalador Linux.
     * @return generador de entradas complementarias del instalador.
     */
    @Bean
    LinuxInstallerBundleWriter linuxInstaller(ObjectMapper mapper) {
        return new LinuxInstallerBundleWriter(mapper);
    }

    /**
     * Compone resolución exacta con pool, límites, cancelación y progreso.
     *
     * @param resolver Puerto que revalida la fuente exacta seleccionada para cada instalador.
     * @param executor Pool global compartido por ventanas de resolución y descarga.
     * @param properties Configuración tipada de destinos, límites o credenciales que utiliza el
     *     componente construido.
     * @param cancellations Registro común que conecta mensajes de cancelación con tareas en vuelo.
     * @param events Emisor común de eventos deterministas de progreso y resultado.
     * @param clock Reloj UTC compartido para eventos, reservas y métricas.
     * @return preparación acotada de fuentes antes de reservar capacidad.
     */
    @Bean
    DownloadResolutionService downloadResolutions(SourceReferenceResolver resolver, ExecutorService executor,
            DownloadProperties properties, DownloadCancellationRegistry cancellations, DownloadEventEmitter events, Clock clock) {
        return new DownloadResolutionService(resolver, executor, properties, cancellations, events, clock);
    }

    /**
     * Envuelve el procesador primero con inbox y después con validación para comprobar el mensaje
     * antes de reservarlo.
     *
     * @param validator Bean Validation aplicado al sobre y contenido antes de reservar el inbox.
     * @param inbox Deduplicación y reserva temporal del comando recibido.
     * @param properties Configuración tipada de destinos, límites o credenciales que utiliza el
     *     componente construido.
     * @param processor Coordinador al que se delega después de validar y reservar el evento.
     * @return cadena validación, deduplicación y procesamiento.
     */
    @Bean("downloadJobHandler")
    DownloadJobHandler downloadJobHandler(
            Validator validator,
            InboxRepository inbox,
            DownloadProperties properties,
            DownloadJobProcessor processor) {
        DownloadJobHandler handler = processor::process;
        handler = new InboxDownloadJobHandler(inbox, properties, handler);
        return new ValidatedDownloadJobHandler(validator, handler);
    }

    /**
     * Proporciona el mismo reloj UTC a reservas, eventos y mantenimiento.
     *
     * @return reloj del sistema en UTC.
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Configura el timeout de conexión y desactiva redirecciones automáticas para validar cada
     * destino desde la cadena de políticas.
     *
     * @param properties Configuración tipada de destinos, límites o credenciales que utiliza el
     *     componente construido.
     * @return cliente de transferencias externas.
     */
    @Bean
    HttpClient downloadHttpClient(DownloadProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * Selecciona el resolutor DNS del JDK para comprobar todas las direcciones de un host.
     *
     * @return adaptador de resolución DNS.
     */
    @Bean
    HostResolver hostResolver() {
        return new DnsHostResolver();
    }

    /**
     * Implementa el puerto de URI pública mediante comprobación HTTPS y resolución DNS.
     *
     * @param hostResolver Consulta DNS utilizada por la validación pública de direcciones.
     * @return política de destinos públicos compartida por descargas y accesos manuales.
     */
    @Bean
    PublicHttpsUriPolicy publicHttpsUriPolicy(HostResolver hostResolver) {
        return new PublicHttpsUriPolicy(hostResolver);
    }

    /**
     * Compone HTTP seguro e integridad, dos transferencias por host, reintentos, limpieza de
     * parciales y métricas en ese orden de envoltura.
     *
     * @param downloadHttpClient Cliente HTTP sin seguimiento automático de redirecciones.
     * @param publicHttpsUriPolicy Validación de HTTPS, autoridad y direcciones públicas antes de
     *     cada petición.
     * @param properties Configuración tipada de destinos, límites o credenciales que utiliza el
     *     componente construido.
     * @param meterRegistry Registro de límites por host, tiempos y reintentos.
     * @return puerto de descarga con las políticas de producción.
     */
    @Bean
    RemoteDownloader remoteDownloader(
            @Qualifier("downloadHttpClient") HttpClient downloadHttpClient,
            PublicHttpsUriPolicy publicHttpsUriPolicy,
            DownloadProperties properties,
            MeterRegistry meterRegistry) {
        RemoteDownloader downloader = new JdkHttpsRemoteDownloader(
                downloadHttpClient,
                publicHttpsUriPolicy,
                properties);
        downloader = new HostLimitedRemoteDownloader(downloader, meterRegistry, 2);
        downloader = new RetryingRemoteDownloader(downloader, meterRegistry);
        downloader = new PartialFileCleanupRemoteDownloader(downloader);
        return new MeteredRemoteDownloader(downloader, meterRegistry);
    }

    /**
     * Crea conexiones al scraper con el plazo configurado y sin seguir redirecciones
     * automáticamente.
     *
     * @param properties Configuración tipada de destinos, límites o credenciales que utiliza el
     *     componente construido.
     * @return cliente de resolución interna.
     */
    @Bean
    HttpClient sourceResolverHttpClient(DownloadProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * Conecta el cliente interno y la configuración del scraper al puerto de resolución exacta.
     *
     * @param sourceResolverHttpClient Cliente sin redirecciones automáticas para el scraper
     *     interno.
     * @param objectMapper Serializador JSON configurado para los contratos de eventos o peticiones
     *     internas.
     * @param properties Configuración tipada de destinos, límites o credenciales que utiliza el
     *     componente construido.
     * @return adaptador que valida identidad y confianza de la respuesta.
     */
    @Bean
    SourceReferenceResolver sourceReferenceResolver(
            @Qualifier("sourceResolverHttpClient") HttpClient sourceResolverHttpClient,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper,
            SourceResolverProperties properties) {
        return new HttpSourceReferenceResolver(sourceResolverHttpClient, objectMapper, properties);
    }

    /**
     * Configura el plazo de conexión de Core y desactiva redirecciones automáticas.
     *
     * @param properties Configuración tipada de destinos, límites o credenciales que utiliza el
     *     componente construido.
     * @return cliente de metadatos internos.
     */
    @Bean
    HttpClient coreApiHttpClient(CoreApiProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.timeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * Conecta JSON, cliente y configuración de Core a la consulta de metadatos del trabajo.
     *
     * @param coreApiHttpClient Cliente sin redirecciones automáticas para metadatos de Core.
     * @param objectMapper Serializador JSON configurado para los contratos de eventos o peticiones
     *     internas.
     * @param properties Configuración tipada de destinos, límites o credenciales que utiliza el
     *     componente construido.
     * @return adaptador que exige correspondencia exacta de elementos.
     */
    @Bean
    JobItemMetadataLookup jobItemMetadataLookup(
            @Qualifier("coreApiHttpClient") HttpClient coreApiHttpClient,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper,
            CoreApiProperties properties) {
        return new HttpJobItemMetadataLookup(coreApiHttpClient, objectMapper, properties);
    }

    /**
     * Configura destino y credenciales del cliente S3 sin iniciar todavía una subida.
     *
     * @param properties Configuración tipada de destinos, límites o credenciales que utiliza el
     *     componente construido.
     * @return cliente MinIO del almacén interno.
     */
    @Bean
    MinioClient minioClient(StorageProperties properties) {
        return MinioClient.builder()
                .endpoint(properties.endpoint())
                .credentials(properties.accessKey(), properties.secretKey())
                .build();
    }

    /**
     * Asocia el cliente MinIO al bucket y límites de almacenamiento configurados.
     *
     * @param minioClient Cliente autenticado del almacén de objetos.
     * @param properties Configuración tipada de destinos, límites o credenciales que utiliza el
     *     componente construido.
     * @return almacén con subida multipart por streaming.
     */
    @Bean
    ArtifactStore artifactStore(MinioClient minioClient, StorageProperties properties) {
        return new MinioArtifactStore(minioClient, properties);
    }

    /**
     * Selecciona el escritor de ZIP con nombres relativos y permisos UNIX por entrada.
     *
     * @return constructor ZIP por streaming.
     */
    @Bean
    ArchiveBuilder archiveBuilder() {
        return new ZipArchiveBuilder();
    }

    /**
     * Conecta publicación AMQP y el exchange de eventos.
     *
     * @param rabbitTemplate Publicador AMQP con confirmación correlacionada.
     * @param properties Configuración tipada de destinos, límites o credenciales que utiliza el
     *     componente construido.
     * @return publicador que espera confirmación del broker.
     */
    @Bean
    EventPublisher eventPublisher(RabbitTemplate rabbitTemplate, MessagingProperties properties) {
        return new RabbitEventPublisher(rabbitTemplate, properties);
    }

    /**
     * Configura reserva y deduplicación del inbox con JDBC y reloj común.
     *
     * @param jdbcTemplate Acceso SQL al inbox local del worker.
     * @param clock Reloj UTC compartido para eventos, reservas y métricas.
     * @return repositorio local de mensajes procesados.
     */
    @Bean
    InboxRepository inboxRepository(JdbcTemplate jdbcTemplate, Clock clock) {
        return new JdbcInboxRepository(jdbcTemplate, clock);
    }

    /**
     * Crea y arranca un pool fijo con cola acotada al mismo tamaño y rechazo por saturación, usando
     * hilos daemon identificables; Spring lo apaga al cerrar.
     *
     * @param properties Configuración tipada de destinos, límites o credenciales que utiliza el
     *     componente construido.
     * @return ejecutor global de resolución y transferencia.
     */
    @Bean(destroyMethod = "shutdown")
    ExecutorService downloadExecutor(DownloadProperties properties) {
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "artifact-download-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                properties.concurrency(),
                properties.concurrency(),
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.concurrency()),
                factory,
                new ThreadPoolExecutor.AbortPolicy());
        executor.prestartAllCoreThreads();
        return executor;
    }

    /**
     * Configura el semáforo justo de trabajos con los permisos globales y sus métricas.
     *
     * @param properties Configuración tipada de destinos, límites o credenciales que utiliza el
     *     componente construido.
     * @param registry Registro de ocupación y espera de permisos globales.
     * @return gestor de trabajos normales y exclusivos.
     */
    @Bean
    JobCapacity jobCapacity(DownloadProperties properties, MeterRegistry registry) {
        return new JobCapacity(properties.jobConcurrency(), registry);
    }

    /**
     * Configura permisos justos de empaquetado independientes de las ventanas de descarga.
     *
     * @param properties Configuración tipada de destinos, límites o credenciales que utiliza el
     *     componente construido.
     * @return semáforo con packagingConcurrency permisos.
     */
    @Bean("packagingSemaphore")
    Semaphore packagingSemaphore(DownloadProperties properties) {
        return new Semaphore(properties.packagingConcurrency(), true);
    }
}
