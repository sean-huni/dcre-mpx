package za.co.fnb.dcre.mpx.data.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Read-only correlation lookup against {@code man_outbound}, the MRW-owned
 * outbound registry (single writer R-04). MPX NEVER ships the man_outbound
 * changeset in its own changelog (same discipline as IXR vs the crw_* tables);
 * at runtime it lives in the same dcre_man database MRW populates, so MPX reads
 * it directly. {@code out_msg_id} is UNIQUE, so a resolve returns at most one id.
 *
 * <p>Fail-closed correlation (SCRUM-60 canon): the caller excludes a reply whose
 * OrgnlMsgId does not resolve here (WARN + drop, no family fallback, never a
 * guessed route). The row's id is returned only to prove resolution; MPX stores
 * nothing from man_outbound (every persisted column comes from the reply itself).
 */
@Component
public class ManOutboundLookupDao {

    private final JdbcTemplate jdbc;

    public ManOutboundLookupDao(final JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return the outbound registry id when {@code outMsgId} is a known outbound identity, else empty. */
    public Optional<UUID> findByOutMsgId(final String outMsgId) {
        return jdbc.query("SELECT id FROM man_outbound WHERE out_msg_id = ?",
                        (rs, row) -> rs.getObject("id", UUID.class), outMsgId)
                .stream().findFirst();
    }
}
