package za.co.fnb.dcre.mpx.domain;

/**
 * One parsed pain.012 acceptance leg: a mandate reply carries exactly ONE mandate
 * per message (unlike the collections ISR fan-out per Tx). The three correlation
 * identities the AGT response-leg DAG maps through are {@code orgnlMsgId} (the
 * outbound MsgId, correlated to man_outbound.out_msg_id), {@code mndtReqId} (the
 * MRR-minted request id, the reply's business key) and {@code mndtId} (the
 * mandate ref). {@code status} is the MndtSts (ACCP/PDNG/RJCT), {@code reason}
 * the optional Rsn code, and {@code e2e} an opportunistic EndToEndId that the
 * current synthetic contract does not carry (nullable-but-forward-compatible).
 * Identifiers are strings (leading-zero-safe, persistence.md).
 */
public record ManReply(
        String orgnlMsgId,
        String mndtReqId,
        String mndtId,
        String e2e,
        String status,
        String reason) {
}
