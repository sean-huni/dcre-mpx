package za.co.fnb.dcre.mpx.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCRUM-91: MPX is the PBSR leg reader. The leg is a compile-time property of the
 * service, NOT a reply.type launch arg (that arg was MAR's merged-reader shape and
 * is the deviation this refactor removes).
 */
class ReaderServiceLegTest {

    @Test
    void targetTableIsThePbsrLegAndNothingElse() {
        assertThat(ReaderService.TARGET_TABLE).isEqualTo("man_pbsr_resp");
    }
}
