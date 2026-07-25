package za.co.fnb.dcre.mpx.config;

import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import za.co.fnb.dcre.mpx.service.ReaderTasklet;
import za.co.fnb.dcre.platform.batch.CrdbRetryExceptionHandler;
import za.co.fnb.dcre.platform.batch.HeartbeatWriter;
import za.co.fnb.dcre.platform.batch.OutcomeSeamListener;

/**
 * MPX job shape (single tasklet, IXR clone): readerStep ingests one pain.012
 * acceptance leg per launch into the reply-type's response table. Identifying
 * JobParameter: arrival.id (R-16); the reply.type launch arg selects the table.
 * Runs on the default SERIALIZABLE isolation (no READ COMMITTED override; only
 * PRG carries RC per SCRUM-90).
 */
@Configuration
public class MpxJobConfig {

    @Bean
    public Job mpxJob(final JobRepository repo, final PlatformTransactionManager tx, final ReaderTasklet tasklet,
                      final HeartbeatWriter heartbeatWriter,
                      @Value("${dcre.exchange-root}") final String exchangeRoot) {
        // CRDB 40001 retry on the ingest step (the one that WRITES): the response
        // upsert commits in its own REQUIRES_NEW tx inside ReaderService, so the
        // aborts hit the chunk-commit boundary, which only a step-level handler
        // sees. Retry, never skip. The step tx is THIN: a step-level re-run no-ops
        // over the committed identity.
        final Step readerStep = new StepBuilder("readerStep", repo).tasklet(tasklet, tx)
                .exceptionHandler(new CrdbRetryExceptionHandler("MPX")).build();
        // Shared platform-batch seam listener (COMPLETED-gated, constant
        // BUSINESS_ACCEPTED verdict; dev fallback local-mpx-<executionId>) plus the
        // HeartbeatWriter liveness stamp on agt_ops while the job runs.
        return new JobBuilder("mpxJob", repo)
                .listener(new OutcomeSeamListener("mpx", exchangeRoot, execution -> "BUSINESS_ACCEPTED"))
                .listener(heartbeatWriter)
                .start(readerStep)
                .build();
    }
}
