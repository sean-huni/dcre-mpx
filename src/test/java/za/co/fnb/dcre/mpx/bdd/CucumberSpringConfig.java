package za.co.fnb.dcre.mpx.bdd;

import io.cucumber.spring.CucumberContextConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import za.co.fnb.dcre.mpx.AbstractCrdbIT;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Launcher disabled, exchange root under build/, static CRDB container (same bootstrap as MpxJobTest). */
@CucumberContextConfiguration
@SpringBootTest(properties = {"spring.batch.job.enabled=false", "dcre.exchange-root=build/test-exchange"})
public class CucumberSpringConfig {


    @DynamicPropertySource
    static void props(final DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", AbstractCrdbIT.CRDB::getJdbcUrl);
        registry.add("spring.datasource.username", AbstractCrdbIT.CRDB::getUsername);
        registry.add("spring.datasource.password", AbstractCrdbIT.CRDB::getPassword);
    }
}
