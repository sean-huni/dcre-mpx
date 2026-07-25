package za.co.fnb.dcre.mpx.bdd;

import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import za.co.fnb.dcre.mpx.ManOutboundSourceTable;
import za.co.fnb.dcre.mpx.ManReplyFixture;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Glue for the MPX acceptance-reader feature: drives the real mpxJob against CRDB. */
public class MpxSteps {

    @Autowired
    Job mpxJob;

    @Autowired
    JobOperator jobOperator;

    @Autowired
    JdbcTemplate jdbc;

    private Path dir;
    private String responseFile;
    private JobExecution execution;

    @Before
    public void setUp() throws Exception {
        ManOutboundSourceTable.bootstrap(jdbc);
        dir = Files.createTempDirectory("mpx-bdd");
    }

    @Given("the outbound message {string} for request {string} is registered")
    public void outboundRegistered(final String outMsgId, final String mndtReqId) {
        ManOutboundSourceTable.seed(jdbc, outMsgId, mndtReqId, "PAIN009");
    }

    @Given("a pain.012 PBSR reply file {string} answering outbound {string} for request {string} mandate {string} with status {string}")
    public void replyFile(final String file, final String outMsgId, final String mndtReqId,
                          final String mndtId, final String status) throws Exception {
        writeReply(file, ManReplyFixture.leg("PBSR", outMsgId, mndtReqId, mndtId, status, null));
    }

    @Given("a pain.012 PBSR reply file {string} answering outbound {string} for request {string} mandate {string} with status {string} reason {string}")
    public void replyFileWithReason(final String file, final String outMsgId, final String mndtReqId,
                                    final String mndtId, final String status,
                                    final String reason) throws Exception {
        writeReply(file, ManReplyFixture.leg("PBSR", outMsgId, mndtReqId, mndtId, status, reason));
    }

    @Given("a malformed pain.012 PBSR reply file {string} with no original message id")
    public void malformedReplyFile(final String file) throws Exception {
        writeReply(file, "<PBSR>\n  <MndtReqId>MREQ-X</MndtReqId>\n  <MndtId>MND-X</MndtId>\n"
                + "  <MndtSts>ACCP</MndtSts>\n</PBSR>\n");
    }

    @When("the MPX job ingests the reply file")
    public void ingest() throws Exception {
        execution = jobOperator.start(mpxJob, new JobParametersBuilder()
                .addString("arrival.id", UUID.randomUUID().toString(), true)
                .addString("input.file", dir.resolve(responseFile).toString(), false)
                .addString("original.name", responseFile, false)
                .toJobParameters());
    }

    @Then("the job completes")
    public void jobCompletes() {
        assertEquals(BatchStatus.COMPLETED, execution.getStatus());
    }

    @Then("the job fails")
    public void jobFails() {
        assertEquals(BatchStatus.FAILED, execution.getStatus());
    }

    @Then("{int} response row is stored in {string} for the reply file")
    public void oneRowStored(final int expected, final String table) {
        assertEquals(expected, count(table));
    }

    @Then("{int} response rows are stored in {string} for the reply file")
    public void rowsStored(final int expected, final String table) {
        assertEquals(expected, count(table));
    }

    @Then("the response row in {string} records status {string}")
    public void rowStatus(final String table, final String status) {
        assertEquals(status, jdbc.queryForObject(
                "SELECT status FROM " + table + " WHERE response_file=?", String.class, responseFile));
    }

    @Then("the response row in {string} records status {string} and reason {string}")
    public void rowStatusAndReason(final String table, final String status, final String reason) {
        assertEquals(status, jdbc.queryForObject(
                "SELECT status FROM " + table + " WHERE response_file=?", String.class, responseFile));
        assertEquals(reason, jdbc.queryForObject(
                "SELECT reason FROM " + table + " WHERE response_file=?", String.class, responseFile));
    }

    private void writeReply(final String file, final String body) throws Exception {
        responseFile = file;
        Files.writeString(dir.resolve(file), body);
    }

    private int count(final String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE response_file=?",
                Integer.class, responseFile);
    }
}
