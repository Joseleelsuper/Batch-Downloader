package es.ubu.batchdownloader.downloadworker.application;

import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;

/** Recupera tareas por orden de finalización y conserva interrupciones y causas originales. */
final class CompletedTasks {
    private CompletedTasks() {}

    static <T> T await(CompletionService<T> completions) {
        try {
            return completions.take().get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new InfrastructureException("download_job_interrupted", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new InfrastructureException("download_task_failed", cause);
        }
    }
}
