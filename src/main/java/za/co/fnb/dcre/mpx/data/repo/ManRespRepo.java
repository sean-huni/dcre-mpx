package za.co.fnb.dcre.mpx.data.repo;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import za.co.fnb.dcre.mpx.data.model.ManPbsrRespEntity;

import java.util.Optional;
import java.util.UUID;

/**
 * Sole owner of the {@code man_pbsr_resp} write, in the sibling shape every other
 * reader uses (cix IsrRespRepo, mrw ManOutboundRepo): Spring Data JDBC
 * CrudRepository over an entity extending BaseEntity, with the guarded insert as
 * a native {@code @Query}. The table is not injectable and there is no leg
 * branching: the leg is a compile-time property of the service (SCRUM-91; the
 * merged three-table DAO was MAR's shape, and its "one DAO instead of three
 * near-identical repositories" rationale died with the merged reader).
 *
 * <p>The write keys on the FULL business identity (response_file, mndt_req_id):
 * never UPSERT on the PK, CRDB resolves UPSERT on the PK only (persistence.md).
 * DO NOTHING makes a replay a zero-duplicate no-op, and statuses stay
 * truth-on-first-arrival for a given file so a re-run never clobbers.
 *
 * <p>The FULL identity matters most on THIS leg: a delayed debtor authentication
 * lands a SECOND PBSR for the SAME mandate under a distinct file stem, and both
 * rows must persist. Keying on mndt_req_id alone would silently swallow it.
 */
public interface ManRespRepo extends CrudRepository<ManPbsrRespEntity, UUID> {

    /**
     * Guarded idempotent insert of one reply verdict.
     *
     * @return 1 on first arrival, 0 when the identity already stands (replay no-op).
     */
    @Modifying
    @Query("INSERT INTO " + ManPbsrRespEntity.TABLE + """
             (id, response_file, orgnl_msg_id, mndt_id, mndt_req_id, e2e, status, reason)
            VALUES (:#{#e.id}, :#{#e.responseFile}, :#{#e.orgnlMsgId}, :#{#e.mndtId},
                    :#{#e.mndtReqId}, :#{#e.e2e}, :#{#e.status}, :#{#e.reason})
            ON CONFLICT (response_file, mndt_req_id) DO NOTHING""")
    int insertGuarded(@Param("e") ManPbsrRespEntity e);

    /**
     * Fail-closed correlation against {@code man_outbound}, the MRW-owned outbound
     * registry (single writer R-04). MPX NEVER ships the man_outbound changeset in
     * its own changelog (same discipline as CIX vs the crw_* tables); at runtime it
     * lives in the same dcre_man database MRW populates, so MPX reads it directly.
     * {@code out_msg_id} is UNIQUE, so a resolve returns at most one id.
     *
     * <p>The caller EXCLUDES a reply whose OrgnlMsgId does not resolve here (WARN +
     * drop, no family fallback, never a guessed route; SCRUM-60 canon). The id is
     * returned only to prove resolution: MPX stores nothing from man_outbound, every
     * persisted column comes from the reply itself.
     *
     * @return the outbound registry id for a known outbound identity, else empty.
     */
    @Query("SELECT id FROM man_outbound WHERE out_msg_id = :outMsgId")
    Optional<UUID> findOutboundIdByOutMsgId(@Param("outMsgId") String outMsgId);
}
