package za.co.fnb.dcre.mpx.domain;

import org.junit.jupiter.api.Test;
import za.co.fnb.dcre.mpx.ManReplyFixture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pure-parser proofs against the synthetic pain.012 PBSR leg shape (fint_sim_reply.py):
 * the four correlation/verdict fields are mandatory, Rsn is optional, and the
 * current contract carries no EndToEndId so e2e parses NULL (nullable-but-expected,
 * captured opportunistically when a future reply shape does carry it).
 */
class MandateReplyParserTest {

    @Test
    void parsesAcceptLegWithoutReasonAndWithoutE2e() {
        final String text = ManReplyFixture.leg("PBSR", "OUTMSG-1", "MREQ-1", "MND-1", "ACCP", null);

        final ManReply reply = MandateReplyParser.parse(text, "fnbcc01_OUTMSG-1_ISR.xml");

        assertEquals("OUTMSG-1", reply.orgnlMsgId());
        assertEquals("MREQ-1", reply.mndtReqId());
        assertEquals("MND-1", reply.mndtId());
        assertEquals("ACCP", reply.status());
        assertNull(reply.reason(), "no Rsn element on an accept leg");
        assertNull(reply.e2e(), "synthetic pain.012 carries no EndToEndId: e2e is NULL");
    }

    @Test
    void parsesRejectLegKeepingReason() {
        final String text = ManReplyFixture.leg("PBSR", "OUTMSG-2", "MREQ-2", "MND-2", "RJCT", "AC04");

        final ManReply reply = MandateReplyParser.parse(text, "fnbcc01_OUTMSG-2_PBSR.xml");

        assertEquals("RJCT", reply.status());
        assertEquals("AC04", reply.reason());
    }

    @Test
    void capturesEndToEndIdWhenTheReplyCarriesOne() {
        final String text = ManReplyFixture.legWithE2e("PBSR", "OUTMSG-3", "MREQ-3", "MND-3", "E2E-9", "ACCP");

        final ManReply reply = MandateReplyParser.parse(text, "fnbcc01_OUTMSG-3_PBSR.xml");

        assertEquals("E2E-9", reply.e2e(), "opportunistic e2e capture when the reply carries EndToEndId");
    }

    @Test
    void rejectsReplyMissingOriginalMessageId() {
        final String text = """
                <PBSR>
                  <MndtReqId>MREQ-4</MndtReqId>
                  <MndtId>MND-4</MndtId>
                  <MndtSts>ACCP</MndtSts>
                </PBSR>
                """;

        assertThrows(IllegalArgumentException.class,
                () -> MandateReplyParser.parse(text, "fnbcc01_broken_PBSR.xml"),
                "a reply with no OrgnlMsgId is malformed and must fail the job");
    }

    @Test
    void rejectsReplyMissingMandateRequestId() {
        final String text = """
                <PBSR>
                  <OrgnlMsgId>OUTMSG-5</OrgnlMsgId>
                  <MndtId>MND-5</MndtId>
                  <MndtSts>ACCP</MndtSts>
                </PBSR>
                """;

        assertThrows(IllegalArgumentException.class,
                () -> MandateReplyParser.parse(text, "fnbcc01_broken_ISR.xml"),
                "MndtReqId is the reply business key: its absence is fatal");
    }
}
