package es.ubu.batchdownloader.downloadworker.config;

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
 * Define la configuración utilizada por {@code WorkerConfiguration}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
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
    /** Compone validación e idempotencia alrededor del procesador funcional. */
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
     * Ejecuta la operación {@code clock}.
     *
     * @return Resultado producido por {@code clock}.
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Ejecuta la operación {@code downloadHttpClient}.
     *
     * @param properties Valor de {@code properties} utilizado por la operación.
     * @return Resultado producido por {@code downloadHttpClient}.
     */
    @Bean
    HttpClient downloadHttpClient(DownloadProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * Ejecuta la operación {@code hostResolver}.
     *
     * @return Resultado producido por {@code hostResolver}.
     */
    @Bean
    HostResolver hostResolver() {
        return new DnsHostResolver();
    }

    /**
     * Ejecuta la operación {@code publicHttpsUriPolicy}.
     *
     * @param hostResolver Valor de {@code hostResolver} utilizado por la operación.
     * @return Resultado producido por {@code publicHttpsUriPolicy}.
     */
    @Bean
    PublicHttpsUriPolicy publicHttpsUriPolicy(HostResolver hostResolver) {
        return new PublicHttpsUriPolicy(hostResolver);
    }

    /**
     * Ejecuta la operación {@code remoteDownloader}.
     *
     * @param downloadHttpClient Valor de {@code downloadHttpClient} utilizado por la operación.
     * @param publicHttpsUriPolicy Valor de {@code publicHttpsUriPolicy} utilizado por la operación.
     * @param properties Valor de {@code properties} utilizado por la operación.
     * @param meterRegistry registro utilizado por el wrapper de observabilidad.
     * @return Resultado producido por {@code remoteDownloader}.
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
     * Ejecuta la operación {@code sourceResolverHttpClient}.
     *
     * @param properties Valor de {@code properties} utilizado por la operación.
     * @return Resultado producido por {@code sourceResolverHttpClient}.
     */
    @Bean
    HttpClient sourceResolverHttpClient(DownloadProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * Ejecuta la operación {@code sourceReferenceResolver}.
     *
     * @param sourceResolverHttpClient Valor de {@code sourceResolverHttpClient} utilizado por la
     *     operación.
     * @param objectMapper Valor de {@code objectMapper} utilizado por la operación.
     * @param properties Valor de {@code properties} utilizado por la operación.
     * @return Resultado producido por {@code sourceReferenceResolver}.
     */
    @Bean
    SourceReferenceResolver sourceReferenceResolver(
            @Qualifier("sourceResolverHttpClient") HttpClient sourceResolverHttpClient,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper,
            SourceResolverProperties properties) {
        return new HttpSourceReferenceResolver(sourceResolverHttpClient, objectMapper, properties);
    }

    /**
     * Ejecuta la operación {@code coreApiHttpClient}.
     *
     * @param properties Valor de {@code properties} utilizado por la operación.
     * @return Resultado producido por {@code coreApiHttpClient}.
     */
    @Bean
    HttpClient coreApiHttpClient(CoreApiProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.timeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * Ejecuta la operación {@code jobItemMetadataLookup}.
     *
     * @param coreApiHttpClient Valor de {@code coreApiHttpClient} utilizado por la operación.
     * @param objectMapper Valor de {@code objectMapper} utilizado por la operación.
     * @param properties Valor de {@code properties} utilizado por la operación.
     * @return Resultado producido por {@code jobItemMetadataLookup}.
     */
    @Bean
    JobItemMetadataLookup jobItemMetadataLookup(
            @Qualifier("coreApiHttpClient") HttpClient coreApiHttpClient,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper,
            CoreApiProperties properties) {
        return new HttpJobItemMetadataLookup(coreApiHttpClient, objectMapper, properties);
    }

    /**
     * Ejecuta la operación {@code minioClient}.
     *
     * @param properties Valor de {@code properties} utilizado por la operación.
     * @return Resultado producido por {@code minioClient}.
     */
    @Bean
    MinioClient minioClient(StorageProperties properties) {
        return MinioClient.builder()
                .endpoint(properties.endpoint())
                .credentials(properties.accessKey(), properties.secretKey())
                .build();
    }

    /**
     * Ejecuta la operación {@code artifactStore}.
     *
     * @param minioClient Valor de {@code minioClient} utilizado por la operación.
     * @param properties Valor de {@code properties} utilizado por la operación.
     * @return Resultado producido por {@code artifactStore}.
     */
    @Bean
    ArtifactStore artifactStore(MinioClient minioClient, StorageProperties properties) {
        return new MinioArtifactStore(minioClient, properties);
    }

    /**
     * Ejecuta la operación {@code archiveBuilder}.
     *
     * @return Resultado producido por {@code archiveBuilder}.
     */
    @Bean
    ArchiveBuilder archiveBuilder() {
        return new ZipArchiveBuilder();
    }

    /**
     * Ejecuta la operación {@code eventPublisher}.
     *
     * @param rabbitTemplate Valor de {@code rabbitTemplate} utilizado por la operación.
     * @param properties Valor de {@code properties} utilizado por la operación.
     * @return Resultado producido por {@code eventPublisher}.
     */
    @Bean
    EventPublisher eventPublisher(RabbitTemplate rabbitTemplate, MessagingProperties properties) {
        return new RabbitEventPublisher(rabbitTemplate, properties);
    }

    /**
     * Ejecuta la operación {@code inboxRepository}.
     *
     * @param jdbcTemplate Valor de {@code jdbcTemplate} utilizado por la operación.
     * @param clock Valor de {@code clock} utilizado por la operación.
     * @return Resultado producido por {@code inboxRepository}.
     */
    @Bean
    InboxRepository inboxRepository(JdbcTemplate jdbcTemplate, Clock clock) {
        return new JdbcInboxRepository(jdbcTemplate, clock);
    }

    /**
     * Ejecuta la operación {@code downloadExecutor}.
     *
     * @param properties Valor de {@code properties} utilizado por la operación.
     * @return Resultado producido por {@code downloadExecutor}.
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

    /** Crea la admisión justa de trabajos normales y exclusivos. */
    @Bean
    JobCapacity jobCapacity(DownloadProperties properties, MeterRegistry registry) {
        return new JobCapacity(properties.jobConcurrency(), registry);
    }

    /** Limita la escritura intensiva sobre el único SSD. */
    @Bean("packagingSemaphore")
    Semaphore packagingSemaphore(DownloadProperties properties) {
        return new Semaphore(properties.packagingConcurrency(), true);
    }
}
