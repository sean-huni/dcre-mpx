package za.co.fnb.dcre.mpx;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCRUM-91 changeset-identity guard: man_pbsr_resp changed owning service (mar -> mpx) and
 * changelog filename, so on a live DB the table already exists. The guarded changeset must
 * MARK_RAN, never re-execute DDL. Four fixtures: fresh DB, legacy end-state (table already
 * present from mar), half-migrated (table without the unique constraint), double-apply.
 */
class MpxLegacyStateIT extends AbstractCrdbIT {

    /** The shape MAR's mar-001-man-pbsr-resp left behind on every already-migrated database. */
    private static final String LEGACY_PBSR_TABLE = """
            CREATE TABLE IF NOT EXISTS man_pbsr_resp (
              id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
              response_file VARCHAR(128) NOT NULL, orgnl_msg_id VARCHAR(35) NOT NULL,
              mndt_id VARCHAR(35) NOT NULL, mndt_req_id VARCHAR(35) NOT NULL,
              e2e VARCHAR(35), status VARCHAR(8) NOT NULL, reason VARCHAR(8),
              version BIGINT NOT NULL DEFAULT 0,
              created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
              updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
              CONSTRAINT uq_man_pbsr_resp_file_mndt_req UNIQUE (response_file, mndt_req_id))""";

    /**
     * The HALF-MIGRATED shape (SCRUM-91 review R5): the table stands but the unique
     * constraint does not. One precondition guarding both statements MARK_RANs the
     * whole changeset here, leaving the runtime ON CONFLICT (response_file,
     * mndt_req_id) with no constraint to arbitrate on, so the idempotency guarantee
     * is silently gone. The constraint gets its own changeset guarded on the schema
     * state IT transforms.
     */
    private static final String HALF_MIGRATED_PBSR_TABLE = """
            CREATE TABLE IF NOT EXISTS man_pbsr_resp (
              id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
              response_file VARCHAR(128) NOT NULL, orgnl_msg_id VARCHAR(35) NOT NULL,
              mndt_id VARCHAR(35) NOT NULL, mndt_req_id VARCHAR(35) NOT NULL,
              e2e VARCHAR(35), status VARCHAR(8) NOT NULL, reason VARCHAR(8),
              version BIGINT NOT NULL DEFAULT 0,
              created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
              updated_at TIMESTAMPTZ NOT NULL DEFAULT now())""";

    /** The runtime guarded write, byte-identical in shape to ManRespRepo.insertGuarded. */
    private static final String GUARDED_INSERT = """
            INSERT INTO man_pbsr_resp (id, response_file, orgnl_msg_id, mndt_id, mndt_req_id, status)
            VALUES (gen_random_uuid(), 'F1', 'OUT-1', 'MND-1', 'MREQ-1', 'ACCP')
            ON CONFLICT (response_file, mndt_req_id) DO NOTHING""";

    @Test
    void aHalfMigratedTableGainsTheUniqueConstraintAndOnConflictStillArbitrates() throws Exception {
        jdbc.execute(HALF_MIGRATED_PBSR_TABLE);

        runLiquibase();
        runLiquibase();

        assertThat(jdbc.update(GUARDED_INSERT)).isOne();
        assertThat(jdbc.update(GUARDED_INSERT))
                .as("ON CONFLICT (response_file, mndt_req_id) needs the constraint to arbitrate on")
                .isZero();

        assertThat(execTypeOf("mpx-001-man-pbsr-resp")).isEqualTo("MARK_RAN");
        assertThat(execTypeOf("mpx-001-man-pbsr-resp-uq"))
                .as("the constraint is guarded on its OWN schema state, so it still executes")
                .isEqualTo("EXECUTED");
        assertThat(countIndexesOn("man_pbsr_resp")).isEqualTo(2);
    }

    @Test
    void preCreatedPbsrTableMarksTheChangesetRan() throws Exception {
        jdbc.execute(LEGACY_PBSR_TABLE);

        runLiquibase();
        runLiquibase();

        assertThat(execTypeOf("mpx-001-man-pbsr-resp")).isEqualTo("MARK_RAN");
        assertThat(execTypeOf("mpx-001-man-pbsr-resp-uq"))
                .as("the constraint already stands, so its own guard converges too")
                .isEqualTo("MARK_RAN");
        assertThat(countIndexesOn("man_pbsr_resp")).isEqualTo(2);
    }

    @Test
    void aFreshDatabaseExecutesTheChangesetAndDoubleApplyIsANoOp() throws Exception {
        runLiquibase();
        runLiquibase();

        assertThat(execTypeOf("mpx-001-man-pbsr-resp")).isEqualTo("EXECUTED");
        assertThat(execTypeOf("mpx-001-man-pbsr-resp-uq")).isEqualTo("EXECUTED");
        assertThat(countIndexesOn("man_pbsr_resp")).isEqualTo(2);
    }

    @Test
    void mpxShipsNeitherTheIsrNorTheSbsrTable() throws Exception {
        runLiquibase();

        assertThat(countIndexesOn("man_isr_resp")).isZero();
        assertThat(countIndexesOn("man_sbsr_resp")).isZero();
    }
}
