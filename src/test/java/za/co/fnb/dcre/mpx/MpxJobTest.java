package za.co.fnb.dcre.mpx;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End-to-end MPX job proofs: the real mpxJob, launched through JobOperator with
 * the identifying arrival.id plus input.file / original.name, reads the pain.012
 * PBSR file and lands one row in man_pbsr_resp. SCRUM-91: the launch contract
 * carries NO reply.type arg, so a job launched with nothing but the file still
 * ingests the PBSR leg. Replay under a fresh job instance is a zero-duplicate no-op.
 */
@SpringBootTest(properties = {"spring.batch.job.enabled=false", "dcre.exchange-root=build/test-exchange"})
class MpxJobTest {


    @DynamicPropertySource
    static void props(final DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", AbstractCrdbIT.CRDB::getJdbcUrl);
        registry.add("spring.datasource.username", AbstractCrdbIT.CRDB::getUsername);
        registry.add("spring.datasource.password", AbstractCrdbIT.CRDB::getPassword);
    }

    @TempDir
    static Path dir;

    @Autowired
    Job mpxJob;

    @Autowired
    JobOperator jobOperator;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void manOutbound() {
        ManOutboundSourceTable.bootstrap(jdbc);
    }

    @Test
    void theJobLandsThePbsrLegWithoutAnyReplyTypeArgAndReplayIsNoOp() throws Exception {
        ManOutboundSourceTable.seed(jdbc, "OUT-J1", "MREQ-J1", "PAIN009");
        final String original = "fnbcc01_OUT-J1_PBSR.xml";
        final Path input = dir.resolve(original);
        Files.writeString(input, ManReplyFixture.leg("PBSR", "OUT-J1", "MREQ-J1", "MND-J1", "ACCP", null));

        final JobExecution run = launch(input, original);
        assertEquals(BatchStatus.COMPLETED, run.getStatus());
        assertEquals(1, count("man_pbsr_resp", original), "the fixed PBSR leg selected man_pbsr_resp");
        assertEquals("ACCP", jdbc.queryForObject(
                "SELECT status FROM man_pbsr_resp WHERE response_file=?", String.class, original));

        final JobExecution replay = launch(input, original);
        assertEquals(BatchStatus.COMPLETED, replay.getStatus());
        assertEquals(1, count("man_pbsr_resp", original),
                "replay is a no-op via ON CONFLICT (response_file, mndt_req_id)");
    }

    @Test
    void aRejectedLegKeepsItsReasonCode() throws Exception {
        ManOutboundSourceTable.seed(jdbc, "OUT-J2", "MREQ-J2", "PAIN009");
        final String original = "fnbcc01_OUT-J2_PBSR.xml";
        final Path input = dir.resolve(original);
        Files.writeString(input, ManReplyFixture.leg("PBSR", "OUT-J2", "MREQ-J2", "MND-J2", "RJCT", "AC04"));

        final JobExecution run = launch(input, original);

        assertEquals(BatchStatus.COMPLETED, run.getStatus());
        assertEquals("RJCT", jdbc.queryForObject(
                "SELECT status FROM man_pbsr_resp WHERE response_file=?", String.class, original));
        assertEquals("AC04", jdbc.queryForObject(
                "SELECT reason FROM man_pbsr_resp WHERE response_file=?", String.class, original));
    }

    private JobExecution launch(final Path input, final String original) throws Exception {
        return jobOperator.start(mpxJob, new JobParametersBuilder()
                .addString("arrival.id", UUID.randomUUID().toString(), true)
                .addString("input.file", input.toString(), false)
                .addString("original.name", original, false)
                .toJobParameters());
    }

    private int count(final String table, final String responseFile) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE response_file=?",
                Integer.class, responseFile);
    }
}
