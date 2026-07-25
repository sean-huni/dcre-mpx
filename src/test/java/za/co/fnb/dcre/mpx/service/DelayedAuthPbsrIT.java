package za.co.fnb.dcre.mpx.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import za.co.fnb.dcre.mpx.ManOutboundSourceTable;
import za.co.fnb.dcre.mpx.ManReplyFixture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCRUM-91: the debtor's delayed authentication arrives as a SECOND PBSR for the same
 * mandate under a DISTINCT file stem ({@code <stem>-AUTH_PBSR.xml}). Both rows must
 * persist: the business identity is (response_file, mndt_req_id), so a distinct file is
 * a distinct row, and the later one is what the pick view selects. This is the ONE
 * behavior MPX has that the ISR and SBSR legs do not, so it gets its own suite.
 *
 * <p>The mirror-image proof is here too: a replay of the SAME file is a zero-duplicate
 * no-op. Together the two tests pin the key at exactly the right width: narrower
 * (mndt_req_id alone) would swallow the authentication, wider (adding status) would
 * duplicate on replay.
 *
 * <p>Driving the real {@link ReaderService} needs a Spring context, so this is a
 * {@code @SpringBootTest} in the {@code MpxReaderIT} mould, NOT a subclass of the
 * non-Spring migration harness AbstractCrdbIT (whose whole point is that Liquibase has
 * NOT run yet). It shares MpxReaderIT's container rather than starting a fifth CRDB:
 * the static-container pattern never stops them, and the fixtures use disjoint keys.
 */
@SpringBootTest(properties = {"spring.batch.job.enabled=false"})
class DelayedAuthPbsrIT {

    @DynamicPropertySource
    static void props(final DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MpxReaderIT.CRDB::getJdbcUrl);
        registry.add("spring.datasource.username", MpxReaderIT.CRDB::getUsername);
        registry.add("spring.datasource.password", MpxReaderIT.CRDB::getPassword);
    }

    @Autowired
    ReaderService service;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void manOutbound() {
        ManOutboundSourceTable.bootstrap(jdbc);
    }

    @Test
    void bothPbsrRowsForOneMandatePersist() {
        ManOutboundSourceTable.seed(jdbc, "OUT-MSG-1", "MREQ0001", "PAIN009");

        assertThat(service.ingest(pbsr("OUT-MSG-1", "MREQ0001", "PDNG"), "REPLY-1_PBSR.xml")).isEqualTo(1);
        assertThat(service.ingest(pbsr("OUT-MSG-1", "MREQ0001", "ACCP"), "REPLY-1-AUTH_PBSR.xml")).isEqualTo(1);

        assertThat(countFor("MREQ0001"))
                .as("the delayed authentication is a distinct response_file, so both rows stand")
                .isEqualTo(2);
        assertThat(statusOf("REPLY-1_PBSR.xml")).isEqualTo("PDNG");
        assertThat(statusOf("REPLY-1-AUTH_PBSR.xml")).isEqualTo("ACCP");
    }

    @Test
    void replayOfTheSameFileIsAZeroDuplicateNoOp() {
        ManOutboundSourceTable.seed(jdbc, "OUT-MSG-2", "MREQ0002", "PAIN009");

        assertThat(service.ingest(pbsr("OUT-MSG-2", "MREQ0002", "ACCP"), "REPLY-2_PBSR.xml")).isEqualTo(1);
        assertThat(service.ingest(pbsr("OUT-MSG-2", "MREQ0002", "ACCP"), "REPLY-2_PBSR.xml")).isEqualTo(0);

        assertThat(countFor("MREQ0002"))
                .as("the same file twice is one row: ON CONFLICT (response_file, mndt_req_id) DO NOTHING")
                .isEqualTo(1);
    }

    private static String pbsr(final String outMsgId, final String mndtReqId, final String status) {
        return ManReplyFixture.leg("PBSR", outMsgId, mndtReqId, "MND-" + mndtReqId, status, null);
    }

    private int countFor(final String mndtReqId) {
        return jdbc.queryForObject("SELECT count(*) FROM man_pbsr_resp WHERE mndt_req_id = ?",
                Integer.class, mndtReqId);
    }

    private String statusOf(final String responseFile) {
        return jdbc.queryForObject("SELECT status FROM man_pbsr_resp WHERE response_file = ?",
                String.class, responseFile);
    }
}
