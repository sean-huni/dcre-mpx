package za.co.fnb.dcre.mpx;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCRUM-107 convergence proofs for the v1 baseline. man_pbsr_resp has TWO creators on one
 * dcre_man: this service's 001-man-pbsr-resp.xml, and MRG's 004-man-views.xml changeset
 * 004-bootstrap-man-pbsr-resp-mrg, which pre-creates table and unique constraint so
 * mnd_pbsr_pick has a source. Nothing serializes the ten M-services' Liquibase runs, so MPX
 * must converge whichever order it arrives in and must never re-execute DDL. Four fixtures:
 * MPX first (empty database), MRG first (table and constraint already stand), a table
 * standing WITHOUT the constraint, and double-apply.
 */
class MpxConvergenceStateIT extends AbstractCrdbIT {

    /** The shape MRG's 004-bootstrap-man-pbsr-resp-mrg leaves behind when it wins the race. */
    private static final String PRE_CREATED_PBSR_TABLE = """
            CREATE TABLE man_pbsr_resp (
              id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
              response_file VARCHAR(128) NOT NULL, orgnl_msg_id VARCHAR(35) NOT NULL,
              mndt_id VARCHAR(35) NOT NULL, mndt_req_id VARCHAR(35) NOT NULL,
              e2e VARCHAR(35), status VARCHAR(8) NOT NULL, reason VARCHAR(8),
              version BIGINT NOT NULL DEFAULT 0,
              created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
              updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
              CONSTRAINT uq_man_pbsr_resp_file_mndt_req UNIQUE (response_file, mndt_req_id))""";

    /**
     * The state that makes the unique constraint its OWN changeset (SCRUM-91 review R5): the
     * table stands but the constraint does not. One precondition guarding both statements would
     * MARK_RAN the whole changeset here, leaving the runtime ON CONFLICT (response_file,
     * mndt_req_id) with no constraint to arbitrate on, so the idempotency guarantee is silently
     * gone. The constraint is therefore guarded on the schema state IT transforms, which is what
     * this fixture proves, independently of which writer put the table there.
     */
    private static final String PBSR_TABLE_WITHOUT_CONSTRAINT = """
            CREATE TABLE man_pbsr_resp (
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
    void aTableWithoutTheConstraintGainsItAndOnConflictStillArbitrates() throws Exception {
        jdbc.execute(PBSR_TABLE_WITHOUT_CONSTRAINT);

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
    void aTablePreCreatedByMrgMarksTheChangesetRan() throws Exception {
        jdbc.execute(PRE_CREATED_PBSR_TABLE);

        runLiquibase();
        runLiquibase();

        assertThat(execTypeOf("mpx-001-man-pbsr-resp")).isEqualTo("MARK_RAN");
        assertThat(execTypeOf("mpx-001-man-pbsr-resp-uq"))
                .as("the constraint already stands, so its own guard converges too")
                .isEqualTo("MARK_RAN");
        assertThat(countIndexesOn("man_pbsr_resp")).isEqualTo(2);
    }

    @Test
    void anEmptyDatabaseExecutesTheChangesetAndDoubleApplyIsANoOp() throws Exception {
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
