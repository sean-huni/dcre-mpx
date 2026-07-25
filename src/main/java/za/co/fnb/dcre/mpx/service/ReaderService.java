package za.co.fnb.dcre.mpx.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import za.co.fnb.dcre.mpx.data.model.ManPbsrRespEntity;
import za.co.fnb.dcre.mpx.data.repo.ManRespRepo;
import za.co.fnb.dcre.mpx.domain.ManReply;
import za.co.fnb.dcre.mpx.domain.MandateReplyParser;

import java.util.Optional;
import java.util.UUID;

/**
 * Business tier: parses one pain.012 PBSR acceptance leg, correlates it fail-closed
 * to the MRW outbound registry, and writes it to the ONE response table this
 * service owns.
 *
 * <p>SCRUM-91: the leg is a compile-time property of the service, NOT a
 * {@code reply.type} launch arg. MPX is the PBSR leg reader, the exact mirror of
 * collections PXR; MIX and MSX are its ISR and SBSR twins. That triplication IS
 * the fleet pattern (ixr/sxr/pxr); the merged three-table reader was MAR's shape
 * and is the deviation this refactor removes.
 *
 * <p>DELAYED AUTHENTICATION, the one behavior only this leg has: the debtor may
 * authenticate late, so a SECOND PBSR arrives for the SAME mandate under a
 * DISTINCT file stem ({@code <stem>-AUTH_PBSR.xml}). BOTH rows must persist. The
 * business identity is (response_file, mndt_req_id), so a distinct file is a
 * distinct row and neither arrival suppresses the other; the precedence view picks
 * the later one. This is exactly why the idempotency key is the FULL identity and
 * never mndt_req_id alone (engineering.md: a key narrower than the entity's
 * uniqueness tuple silently swallows a distinct entity's output).
 *
 * <p>[SYNTHETIC-CONTRACT R-35/A-60] A pain.012 message carries exactly ONE
 * mandate, so the commit unit is the single reply row (not the collections ISR
 * per-Tx fan-out). The write still runs in its OWN REQUIRES_NEW transaction under
 * CrdbRetry, giving the same resume-zero-dup guarantee as the fleet slice-commit
 * pattern: a 40001 abort retries in a fresh tx, and a re-run no-ops over the
 * committed identity via ON CONFLICT (response_file, mndt_req_id) DO NOTHING.
 *
 * <p>CORRELATION is fail-closed (SCRUM-60 canon): the reply's OrgnlMsgId must
 * resolve to a known man_outbound.out_msg_id or the reply is WARNed and EXCLUDED
 * (never ingested, never a guessed family fallback). A known outbound identity is
 * the only evidence the route is real.
 */
@Service
public class ReaderService {

    /**
     * The ONE response table this service owns. MPX is the PBSR leg (mirror of
     * collections PXR). This is an ALIAS of the single literal on the entity, not a
     * second one: the guarded insert reads the same constant, so the leg assertion
     * and the write target cannot drift apart (SCRUM-91 review R1).
     */
    public static final String TARGET_TABLE = ManPbsrRespEntity.TABLE;

    private static final Logger log = LoggerFactory.getLogger(ReaderService.class);

    private final ManRespRepo respRepo;
    private final TransactionTemplate sliceTx;

    public ReaderService(final ManRespRepo respRepo, final PlatformTransactionManager txManager) {
        this.respRepo = respRepo;
        // Own REQUIRES_NEW transaction per write: a CRDB 40001 abort poisons the
        // surrounding transaction (25P02 on any further statement), so each retry
        // needs a fresh transaction (same shape as the pxr sliced ingest).
        this.sliceTx = new TransactionTemplate(txManager);
        this.sliceTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * @return the number of rows this run INSERTED, which is 1 only on a reply's
     * FIRST arrival. It is 0 in two distinct cases the caller cannot tell apart:
     * the reply was excluded fail-closed on an unknown outbound identity (nothing
     * was written and nothing stands), or the identity (response_file, mndt_req_id)
     * already stood so ON CONFLICT DO NOTHING wrote nothing on a replay. The value
     * lands in the JobExecutionContext as {@code "rows"}, where that conflation is
     * live: {@code rows=0} is NOT evidence of a missing row, and the exclusion is
     * distinguishable only by the {@code reason=UNKNOWN_OUTBOUND_MSG} WARN. Neither
     * case fails the job: an exclusion is the fail-closed contract and a replay is
     * the resume no-op (SCRUM-91 review R4).
     */
    public int ingest(final String fileText, final String responseFile) {
        final ManReply reply = MandateReplyParser.parse(fileText, responseFile);
        final Optional<UUID> outbound = respRepo.findOutboundIdByOutMsgId(reply.orgnlMsgId());
        if (outbound.isEmpty()) {
            log.warn("excluded stage=MPX mndtReqId={} reason=UNKNOWN_OUTBOUND_MSG orgnlMsgId={} file={}",
                    reply.mndtReqId(), reply.orgnlMsgId(), responseFile);
            return 0;
        }
        return writeSlice(responseFile, reply);
    }

    /** One reply row = one committed unit: fresh REQUIRES_NEW tx per bounded-retry attempt. */
    private int writeSlice(final String responseFile, final ManReply reply) {
        final ManPbsrRespEntity row = ManPbsrRespEntity.of(responseFile, reply.orgnlMsgId(), reply.mndtId(),
                reply.mndtReqId(), reply.e2e(), reply.status(), reply.reason());
        return CrdbRetry.run("ingest leg=PBSR file=%s".formatted(responseFile),
                () -> sliceTx.execute(status -> respRepo.insertGuarded(row)));
    }
}
