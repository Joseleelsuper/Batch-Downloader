package es.ubu.batchdownloader.downloadworker.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.concurrent.ExecutorCompletionService;
import org.junit.jupiter.api.Test;

/** Protege la propagación de resultados, causas e interrupciones compartida por ambos pipelines. */
class CompletedTasksTest {
    @Test
    void returnsCompletedResult() {
        var completions = new ExecutorCompletionService<String>(Runnable::run);
        completions.submit(() -> "completed");
        assertThat(CompletedTasks.await(completions)).isEqualTo("completed");
    }

    @Test
    void preservesRuntimeFailure() {
        var completions = new ExecutorCompletionService<String>(Runnable::run);
        var failure = new IllegalStateException("storage_unavailable");
        completions.submit(() -> { throw failure; });
        assertThatThrownBy(() -> CompletedTasks.await(completions)).isSameAs(failure);
    }

    @Test
    void wrapsCheckedFailureWithoutLosingItsCause() {
        var completions = new ExecutorCompletionService<String>(Runnable::run);
        var failure = new IOException("disk_unavailable");
        completions.submit(() -> { throw failure; });
        assertThatThrownBy(() -> CompletedTasks.await(completions))
                .isInstanceOf(InfrastructureException.class)
                .hasMessage("download_task_failed").hasCause(failure);
    }

    @Test
    void restoresInterruptFlagWhenWaitingIsInterrupted() {
        var completions = new ExecutorCompletionService<String>(Runnable::run);
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> CompletedTasks.await(completions))
                    .isInstanceOf(InfrastructureException.class).hasMessage("download_job_interrupted");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}
