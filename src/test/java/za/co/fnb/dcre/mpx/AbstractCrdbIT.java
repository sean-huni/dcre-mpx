package za.co.fnb.dcre.mpx;

import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.CockroachContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

/**
 * Migration-proof harness: a real CRDB (the same v26.2.3 image and the same
 * {@code static { start() }} bootstrap the Spring suites here use), but with NO
 * Spring context, so Liquibase runs ONLY when a test asks it to. That is the
 * whole point: a legacy-state proof has to seed the pre-change schema BEFORE the
 * first migration, which a context-managed Liquibase would already have run.
 *
 * <p>Each test gets a VIRGIN database on the shared container, so a fresh-DB
 * fixture and a legacy-end-state fixture never see each other's Liquibase
 * history (testing.md: migration changes are tested against legacy database
 * states, not only fresh containers).
 *
 * <p>SINGLETON CONTAINER (SCRUM-91 review R6): {@link #CRDB} is the ONE CockroachDB
 * this whole module's test run starts, and every Spring suite points its
 * {@code @DynamicPropertySource} here rather than declaring a container of its own.
 * Four containers per service was 12 per three-service run, and the
 * {@code static { start() }} pattern never stops them (testing.md: exited DB
 * containers accumulate until the Docker VM stalls). The suites are isolated by
 * DISJOINT fixture keys, not by separate databases, except this harness which mints
 * a virgin database per test because a migration proof needs one.
 */
public abstract class AbstractCrdbIT {

    private static final String CHANGELOG = "classpath:db/changelog/db.changelog-master.xml";
    private static final String HISTORY_TABLE = "mpx_databasechangelog";
    private static final String HISTORY_LOCK_TABLE = "mpx_databasechangeloglock";

    public static final CockroachContainer CRDB =
            new CockroachContainer(DockerImageName.parse("cockroachdb/cockroach:v26.2.3"));

    static {
        CRDB.start();
    }

    JdbcTemplate jdbc;

    private DriverManagerDataSource dataSource;

    @BeforeEach
    void virginDatabase() {
        final String database = "mpx_it_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(dataSourceFor(null)).execute("CREATE DATABASE " + database);
        dataSource = dataSourceFor(database);
        jdbc = new JdbcTemplate(dataSource);
    }

    /** Apply the whole master changelog exactly as the running service would. */
    void runLiquibase() throws Exception {
        final SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog(CHANGELOG);
        liquibase.setDatabaseChangeLogTable(HISTORY_TABLE);
        liquibase.setDatabaseChangeLogLockTable(HISTORY_LOCK_TABLE);
        liquibase.setResourceLoader(new DefaultResourceLoader());
        liquibase.afterPropertiesSet();
    }

    /** @return EXECUTED when the changeset ran its DDL, MARK_RAN when a precondition converged it. */
    String execTypeOf(final String changeSetId) {
        return jdbc.queryForObject("SELECT exectype FROM " + HISTORY_TABLE + " WHERE id = ?",
                String.class, changeSetId);
    }

    /** @return every index on the table, primary key included (re-executed DDL would change this). */
    int countIndexesOn(final String table) {
        return jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE tablename = ?",
                Integer.class, table);
    }

    private DriverManagerDataSource dataSourceFor(final String database) {
        final DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setUrl(database == null ? CRDB.getJdbcUrl() : jdbcUrlFor(database));
        ds.setUsername(CRDB.getUsername());
        ds.setPassword(CRDB.getPassword());
        return ds;
    }

    private static String jdbcUrlFor(final String database) {
        final String base = CRDB.getJdbcUrl();
        final int query = base.indexOf('?');
        final String head = query < 0 ? base : base.substring(0, query);
        final String tail = query < 0 ? "" : base.substring(query);
        return head.substring(0, head.lastIndexOf('/') + 1) + database + tail;
    }
}
