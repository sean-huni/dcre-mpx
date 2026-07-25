package za.co.fnb.dcre.mpx.data.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import za.co.fnb.dcre.mpx.domain.ManReply;

/**
 * Sole owner of the man_pbsr_resp write. The table is a compile-time constant, so
 * there is no injection surface and no leg branching (SCRUM-91: the merged
 * three-table DAO was MAR's shape). The write is the guarded idempotent INSERT
 * keyed on the FULL business identity (response_file, mndt_req_id): never UPSERT
 * on the PK, CRDB resolves UPSERT on the PK only (persistence.md). DO NOTHING
 * makes a replay a zero-duplicate no-op.
 *
 * <p>The FULL identity matters most on THIS leg: a delayed debtor authentication
 * lands a SECOND PBSR for the SAME mandate under a distinct file stem, and both
 * rows must persist. Keying on mndt_req_id alone would silently swallow it.
 *
 * <p>Statuses are truth-on-first-arrival for a given file, so a re-run must not
 * clobber. id/version/created_at/updated_at fill from the column defaults; e2e is
 * nullable (the current synthetic pain.012 contract carries no EndToEndId).
 */
@Component
public class ManRespDao {

    private static final String INSERT_SQL = """
            INSERT INTO man_pbsr_resp
                   (response_file, orgnl_msg_id, mndt_id, mndt_req_id, e2e, status, reason)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (response_file, mndt_req_id) DO NOTHING""";

    private final JdbcTemplate jdbc;

    public ManRespDao(final JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return 1 on first arrival, 0 when the identity already stands (replay no-op). */
    public int insertGuarded(final String responseFile, final ManReply reply) {
        return jdbc.update(INSERT_SQL, responseFile, reply.orgnlMsgId(), reply.mndtId(),
                reply.mndtReqId(), reply.e2e(), reply.status(), reply.reason());
    }
}
