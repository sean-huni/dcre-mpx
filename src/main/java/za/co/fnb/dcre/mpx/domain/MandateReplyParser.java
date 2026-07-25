package za.co.fnb.dcre.mpx.domain;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure parser for the SYNTHETIC-CONTRACT (R-35, A-60) pain.012 acceptance leg.
 * One mandate per file; the four correlation/verdict fields are mandatory and a
 * missing one is a malformed reply that fails the job (never a silent skip). Rsn
 * and EndToEndId are optional. No framework, no IO: unit-testable in isolation.
 */
public final class MandateReplyParser {

    private static final Pattern ORGNL_MSG_ID = Pattern.compile("<OrgnlMsgId>([^<]+)</OrgnlMsgId>");
    private static final Pattern MNDT_REQ_ID = Pattern.compile("<MndtReqId>([^<]+)</MndtReqId>");
    private static final Pattern MNDT_ID = Pattern.compile("<MndtId>([^<]+)</MndtId>");
    private static final Pattern MNDT_STS = Pattern.compile("<MndtSts>([^<]+)</MndtSts>");
    private static final Pattern RSN = Pattern.compile("<Rsn>([^<]+)</Rsn>");
    // e2e is nullable-but-forward-compatible: the current synthetic contract carries no
    // EndToEndId, so this matches opportunistically (either the Orgnl* or bare form).
    private static final Pattern E2E = Pattern.compile("<(?:Orgnl)?EndToEndId>([^<]+)</(?:Orgnl)?EndToEndId>");

    private MandateReplyParser() {
    }

    /**
     * @param fileText     the whole pain.012 reply document
     * @param responseFile the landed filename (for the diagnostic on a malformed reply)
     * @return the single parsed mandate reply
     * @throws IllegalArgumentException if any mandatory element is absent
     */
    public static ManReply parse(final String fileText, final String responseFile) {
        return new ManReply(
                required(ORGNL_MSG_ID, fileText, "OrgnlMsgId", responseFile),
                required(MNDT_REQ_ID, fileText, "MndtReqId", responseFile),
                required(MNDT_ID, fileText, "MndtId", responseFile),
                optional(E2E, fileText),
                required(MNDT_STS, fileText, "MndtSts", responseFile),
                optional(RSN, fileText));
    }

    private static String required(final Pattern pattern, final String text, final String tag,
                                   final String responseFile) {
        final Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            throw new IllegalArgumentException("pain.012 reply missing <%s>: %s".formatted(tag, responseFile));
        }
        return matcher.group(1).strip();
    }

    private static String optional(final Pattern pattern, final String text) {
        final Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1).strip() : null;
    }
}
