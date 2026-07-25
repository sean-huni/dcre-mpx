package za.co.fnb.dcre.mpx;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * Test bootstrap for {@code man_outbound}, the MRW-owned outbound registry MPX
 * reads for reply-to-outbound correlation. MRW owns this DDL (mrw
 * 001-man-outbound.xml); MPX must never ship a man_outbound changeset in its own
 * changelog, so integration tests create a lookalike table via plain JDBC instead
 * (the same pattern IXR uses via CrwSourceTables). Column shapes copied from the
 * mrw changelog.
 */
public final class ManOutboundSourceTable {

    private ManOutboundSourceTable() {
    }

    public static void bootstrap(final JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS man_outbound (
                    id UUID NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
                    entry_id UUID NOT NULL,
                    out_msg_id VARCHAR(35) NOT NULL,
                    mndt_req_id VARCHAR(35) NOT NULL,
                    pain_type VARCHAR(8) NOT NULL,
                    written_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    version BIGINT NOT NULL DEFAULT 0,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    UNIQUE (entry_id),
                    UNIQUE (out_msg_id)
                )""");
    }

    /**
     * Register an outbound MPX can correlate a reply back to (mrw write-ahead
     * analogue). Idempotent on out_msg_id: one outbound pain.009 gets three reply
     * legs (ISR/SBSR/PBSR), so re-registering the same outbound across legs is a
     * no-op, exactly as mrw's guarded write-ahead (ON CONFLICT DO NOTHING) is.
     */
    public static void seed(final JdbcTemplate jdbc, final String outMsgId, final String mndtReqId,
                            final String painType) {
        jdbc.update("INSERT INTO man_outbound (entry_id, out_msg_id, mndt_req_id, pain_type)"
                + " VALUES (?, ?, ?, ?) ON CONFLICT (out_msg_id) DO NOTHING",
                UUID.randomUUID(), outMsgId, mndtReqId, painType);
    }
}
