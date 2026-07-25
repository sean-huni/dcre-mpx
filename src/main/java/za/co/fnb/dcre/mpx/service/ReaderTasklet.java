package za.co.fnb.dcre.mpx.service;

import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Thin entry adapter (3-tier, configuration.md point 21): no SQL, no parsing.
 * Extracts the file to read and its landed name (the response_file business key),
 * then delegates the whole ingest to the business tier.
 *
 * <p>SCRUM-91: there is no {@code reply.type} launch arg to read any more. MPX
 * only ever ingests the PBSR leg, so the leg is a compile-time property of the
 * service (see {@link ReaderService#TARGET_TABLE}), never a parameter a DAG could
 * get wrong.
 */
@Component
public class ReaderTasklet implements Tasklet {

    private final ReaderService service;

    public ReaderTasklet(final ReaderService service) {
        this.service = service;
    }

    @Override
    public RepeatStatus execute(final StepContribution contribution, final ChunkContext chunkContext) throws Exception {
        final var params = chunkContext.getStepContext().getJobParameters();
        final String fileText = Files.readString(Path.of((String) params.get("input.file")));
        final int rows = service.ingest(fileText, (String) params.get("original.name"));
        chunkContext.getStepContext().getStepExecution().getJobExecution()
                .getExecutionContext().putInt("rows", rows);
        return RepeatStatus.FINISHED;
    }
}
