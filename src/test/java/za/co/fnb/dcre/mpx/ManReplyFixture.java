package za.co.fnb.dcre.mpx;

/**
 * Byte-shape-faithful pain.012 acceptance-leg builder, mirroring the fint-sim
 * reply writer ({@code infra/dcre-infra/scripts/fint_sim_reply.py} {@code _leg}):
 * the synthetic contract carries OrgnlMsgId / MndtReqId / MndtId / MndtSts and an
 * optional Rsn, and NO EndToEndId element. Tests parse exactly what Fintegrate's
 * simulator emits, so a drift in the real reply shape fails a test here.
 */
public final class ManReplyFixture {

    private ManReplyFixture() {
    }

    /** A reply leg exactly as fint_sim_reply.py emits it (no EndToEndId). */
    public static String leg(final String token, final String outMsgId, final String mndtReqId,
                             final String mndtId, final String status, final String reason) {
        final String rsn = reason == null ? "" : "  <Rsn>%s</Rsn>%n".formatted(reason);
        return ("""
                <%s>
                  <!-- SYNTHETIC-CONTRACT pain.012 %s acceptance report (A-60) -->
                  <OrgnlMsgId>%s</OrgnlMsgId>
                  <MndtReqId>%s</MndtReqId>
                  <MndtId>%s</MndtId>
                  <MndtSts>%s</MndtSts>
                %s</%s>
                """).formatted(token, token, outMsgId, mndtReqId, mndtId, status, rsn, token);
    }

    /** A forward-compatible variant that also carries an EndToEndId (e2e capture path). */
    public static String legWithE2e(final String token, final String outMsgId, final String mndtReqId,
                                    final String mndtId, final String e2e, final String status) {
        return ("""
                <%s>
                  <OrgnlMsgId>%s</OrgnlMsgId>
                  <MndtReqId>%s</MndtReqId>
                  <MndtId>%s</MndtId>
                  <OrgnlEndToEndId>%s</OrgnlEndToEndId>
                  <MndtSts>%s</MndtSts>
                </%s>
                """).formatted(token, outMsgId, mndtReqId, mndtId, e2e, status, token);
    }
}
