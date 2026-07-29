package es.ubu.batchdownloader.downloadworker.config;

import es.ubu.batchdownloader.downloadworker.infrastructure.archive.ZipArchiveBuilder;
import es.ubu.batchdownloader.downloadworker.infrastructure.http.DnsHostResolver;
import es.ubu.batchdownloader.downloadworker.infrastructure.http.HostResolver;
import es.ubu.batchdownloader.downloadworker.infrastructure.http.JdkHttpsRemoteDownloader;
import es.ubu.batchdownloader.downloadworker.infrastructure.http.PublicHttpsUriPolicy;
import es.ubu.batchdownloader.downloadworker.infrastructure.messaging.RabbitEventPublisher;
import es.ubu.batchdownloader.downloadworker.infrastructure.persistence.JdbcInboxRepository;
import es.ubu.batchdownloader.downloadworker.infrastructure.storage.MinioArtifactStore;
import es.ubu.batchdownloader.downloadworker.infrastructure.source.HttpJobItemMetadataLookup;
import es.ubu.batchdownloader.downloadworker.infrastructure.source.HttpSourceReferenceResolver;
import es.ubu.batchdownloader.downloadworker.ports.ArchiveBuilder;
import es.ubu.batchdownloader.downloadworker.ports.ArtifactStore;
import es.ubu.batchdownloader.downloadworker.ports.EventPublisher;
import es.ubu.batchdownloader.downloadworker.ports.InboxRepository;
import es.ubu.batchdownloader.downloadworker.ports.JobItemMetadataLookup;
import es.ubu.batchdownloader.downloadworker.ports.RemoteDownloader;
import es.ubu.batchdownloader.downloadworker.ports.SourceReferenceResolver;
import io.minio.MinioClient;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
@EnableConfigurationProperties({
    DownloadProperties.class,
    MessagingProperties.class,
    StorageProperties.class,
    SourceResolverProperties.class,
    CoreApiProperties.class
})
public class WorkerConfiguration {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    HttpClient downloadHttpClient(DownloadProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Bean
    HostResolver hostResolver() {
        return new DnsHostResolver();
    }

    @Bean
    PublicHttpsUriPolicy publicHttpsUriPolicy(HostResolver hostResolver) {
        return new PublicHttpsUriPolicy(hostResolver);
    }

    @Bean
    RemoteDownloader remoteDownloader(
            @Qualifier("downloadHttpClient") HttpClient downloadHttpClient,
            PublicHttpsUriPolicy publicHttpsUriPolicy,
            DownloadProperties properties) {
        return new JdkHttpsRemoteDownloader(downloadHttpClient, publicHttpsUriPolicy, properties);
    }

    @Bean
    HttpClient sourceResolverHttpClient(DownloadProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Bean
    SourceReferenceResolver sourceReferenceResolver(
            @Qualifier("sourceResolverHttpClient") HttpClient sourceResolverHttpClient,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper,
            SourceResolverProperties properties) {
        return new HttpSourceReferenceResolver(sourceResolverHttpClient, objectMapper, properties);
    }

    @Bean
    HttpClient coreApiHttpClient(CoreApiProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.timeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Bean
    JobItemMetadataLookup jobItemMetadataLookup(
            @Qualifier("coreApiHttpClient") HttpClient coreApiHttpClient,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper,
            CoreApiProperties properties) {
        return new HttpJobItemMetadataLookup(coreApiHttpClient, objectMapper, properties);
    }

    @Bean
    MinioClient minioClient(StorageProperties properties) {
        return MinioClient.builder()
                .endpoint(properties.endpoint())
                .credentials(properties.accessKey(), properties.secretKey())
                .build();
    }

    @Bean
    ArtifactStore artifactStore(MinioClient minioClient, StorageProperties properties) {
        return new MinioArtifactStore(minioClient, properties);
    }

    @Bean
    ArchiveBuilder archiveBuilder() {
        return new ZipArchiveBuilder();
    }

    @Bean
    EventPublisher eventPublisher(RabbitTemplate rabbitTemplate, MessagingProperties properties) {
        return new RabbitEventPublisher(rabbitTemplate, properties);
    }

    @Bean
    InboxRepository inboxRepository(JdbcTemplate jdbcTemplate, Clock clock) {
        return new JdbcInboxRepository(jdbcTemplate, clock);
    }

    @Bean(destroyMethod = "shutdown")
    ExecutorService downloadExecutor(DownloadProperties properties) {
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "artifact-download-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newFixedThreadPool(properties.concurrency(), factory);
    }
}
