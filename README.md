# dcre-mpx

> Part of the DCRE fleet. For the fleet map, the rulings and the diagrams that specify every stage, start at the [DCRE design register](https://github.com/sean-huni/dcre-design-register); the complete list of live repositories is its [Repositories](https://github.com/sean-huni/dcre-design-register#repositories) table.

PBSR response reader for DCRE Mandates: ingests one pain.012 PBSR acceptance leg into `man_pbsr_resp`, one verdict row per reply file.

## What it does

**Position in the fleet.** Stage `MPX`, mandates family, RES leg, arrival-launched. AGT (`RouteDags.FINT_RESP_MAN`, route `fint-resp-man`, flow `MAN`) holds the mandates response DAG as three token-picked leg readers, `MIX`, `MSX` and `MPX`, with no fixed entry, no successor edges and no responder: the `_PBSR` filename token selects `MPX`, exactly one reader runs per reply arrival, and it is terminal for that arrival. A reply with no recognised token launches nothing and the arrival stays open for the reconciler (fail closed). Upstream: `MRW`, whose `man_outbound` registry every reply must correlate to. Downstream: no DAG successor; `MRG` (clock-launched) reads `man_pbsr_resp` through `mnd_pbsr_pick` and `mnd_ext_status`. Diagram sheet: `dcre-mandates-res` in the design register.

MPX is the final-acceptance leg of the mandates response flow (`MIX | MSX | MPX -> mnd_ext_status -> MRG`). Fintegrate (simulated by dcre-infra `fint_sim_reply.py --mandate`) drops a reply file into a per-client `fint-resp-man/in` exchange directory; AGT selects the reader by the `_PBSR` filename token and launches MPX as a short-lived Kubernetes Job. MPX parses the reply (one `<OrgnlMsgId>` plus `<MndtReqId>`, `<MndtId>`, `<MndtSts>` and an optional `<Rsn>`, [SYNTHETIC-CONTRACT R-35/A-60] shape), correlates it fail-closed to the MRW outbound registry, and writes exactly one `man_pbsr_resp` row. Replaying the same file is a no-op via `INSERT ... ON CONFLICT (response_file, mndt_req_id) DO NOTHING`.

**Launch contract (CHANGED, SCRUM-91).** MPX takes `input.file` and `original.name` and **NO `reply.type` parameter**. The leg is a compile-time property of the service, not a launch argument: MPX only ever writes `man_pbsr_resp`. The merged three-table reader that selected its table from `reply.type` was MAR's shape, and splitting it into three single-leg readers (MIX/MSX/MPX, mirroring the collections CIX/CSX/CPX fleet) is what this refactor exists to do. A DAG cannot mis-route a leg it cannot name.

**Delayed debtor authentication, the one behavior only this leg has.** The debtor may authenticate late, so a SECOND PBSR arrives for the SAME mandate under a distinct file stem (`<stem>-AUTH_PBSR.xml`). BOTH rows must persist. The business identity is `(response_file, mndt_req_id)`, so a distinct file is a distinct row, neither arrival suppresses the other, and MRG's `mnd_pbsr_pick` view ranks a non-`PDNG` status above `PDNG`, then the latest `created_at`. A key narrower than that tuple (say `mndt_req_id` alone) would silently swallow the authentication.

## Architecture and principles

Spring Boot 4.1.0 / Spring Batch 6 / Java 25 on CockroachDB (PostgreSQL driver; tests pin `v26.2.3`). An ephemeral batch job, not a server: `ExitCodeMain` (platform-batch) wires the Batch outcome into the JVM exit code (R-34).

- **SOLID, 3-tier, layer-first packages**: `ReaderTasklet` is a thin entry adapter (no SQL, no parsing) that reads `input.file` and calls one business-tier method; `ReaderService` parses, correlates and writes; persistence happens only through `data/repo/ManRespRepo` (Spring Data JDBC, `ManPbsrRespEntity` extends the platform `BaseEntity`). Packages: `config`, `service`, `domain`, `data/model`, `data/repo`.
- **One literal names the write target**: `ManPbsrRespEntity.TABLE` is read by the `@Table` mapping, by the guarded insert's native `@Query`, and by `ReaderService.TARGET_TABLE`. There is no second table literal to drift out of step, so the leg assertion and the actual write target cannot disagree. On a live `dcre_man` all three leg tables exist, which is exactly what makes a drifted literal a SILENT cross-leg write rather than a loud failure.
- **12FactorApp Alignment: https://12factor.net/**: config strictly from the environment over committed working dev defaults in `application.yml` (a clean clone runs with no `.env` at all), stateless one-shot process, the shared CockroachDB as an attached backing resource.
- **Fail-closed correlation (SCRUM-60 canon)**: the reply's `OrgnlMsgId` must resolve to a known `man_outbound.out_msg_id` or the reply is WARNed and EXCLUDED. Never a guessed family fallback: a known outbound identity is the only evidence the route is real. MPX stores nothing from `man_outbound`; every persisted column comes from the reply itself.
- **Idempotent restart semantics**: `ManRespRepo.insertGuarded` is a native `INSERT ... ON CONFLICT (response_file, mndt_req_id) DO NOTHING` on the FULL business identity (CRDB `UPSERT` arbitrates on the PK only, so a business key needs `ON CONFLICT`). Statuses are truth-on-first-arrival for a given file, so a re-run never clobbers. The write commits in its OWN `REQUIRES_NEW` transaction wrapped by `CrdbRetry`, so a 40001 abort retries in a fresh transaction instead of poisoning the surrounding one.
- **40001 at the step boundary**: `readerStep` registers the shared `CrdbRetryExceptionHandler` (platform-batch) so commit-time serialization aborts retry instead of failing the job.
- **Outcome seam (R-35)**: on `COMPLETED`, `OutcomeSeamListener` writes `BUSINESS_ACCEPTED` to `<exchange-root>/outcomes/<JOB_NAME>`. A non-COMPLETED execution writes nothing; the exit code and the K8s Failed condition are the witnesses, and AGT treats absence as never-success (R-33).
- **Ingest return value**: `ReaderService.ingest` returns the rows this run INSERTED, surfaced in the JobExecutionContext as `"rows"`. `1` means first arrival. `0` conflates two distinct outcomes, a fail-closed exclusion and a replay no-op, and is NOT evidence of a missing row; only the `reason=UNKNOWN_OUTBOUND_MSG` WARN distinguishes them. Neither outcome fails the job.

### Data

Database today: the shared mandates database `dcre_man` (primary datasource `DCRE_DB_URL`, `DCRE_DB_USER`, `DCRE_DB_PASSWORD`), plus `agt_ops` for the platform-batch heartbeat (`DCRE_AGTOPS_DB_URL`, `DCRE_AGTOPS_DB_USER`, `DCRE_AGTOPS_DB_PASSWORD`). Writes: `man_pbsr_resp`, the `MPX_BATCH_*` metadata tables and its Liquibase history tables, plus the guarded shared-core creates and seeds below. Reads: `man_outbound` (MRW-owned, `SELECT id FROM man_outbound WHERE out_msg_id = ?`) for correlation. MPX performs no `spine_state` transition.

`man_pbsr_resp` (Liquibase `db/changelog/2026/07/001-man-pbsr-resp.xml`): `response_file`, `orgnl_msg_id`, `mndt_id`, `mndt_req_id`, `e2e` (nullable, the current synthetic contract carries no `EndToEndId`), `status`, `reason` (nullable), plus `BaseEntity` columns (`version`, `created_at`, `updated_at`); `UNIQUE (response_file, mndt_req_id)`.

The changelog is a v1 baseline (SCRUM-107): every DCRE database is dropped and recreated, so there is no historic schema, no `validCheckSum`, no defensive `IF NOT EXISTS` and no retrofit changesets. The two `MARK_RAN` guards that remain are CONVERGENCE guards, not retrofits: `man_pbsr_resp` has two creators on the one `dcre_man`, this changelog and MRG's `004-man-views.xml` changeset `004-bootstrap-man-pbsr-resp-mrg` (which pre-creates it so `mnd_pbsr_pick` has a source), and nothing serializes the ten M-services' Liquibase runs. The table and its unique constraint are SEPARATE changesets, each guarded on the schema state it transforms (`tableExists` and `indexExists`): folding the constraint into the table changeset would MARK_RAN it too against a database where the table stands without the constraint, silently leaving the runtime `ON CONFLICT` with nothing to arbitrate on.

Shared-core shapes (`account`, `account_type`, `mandate_reason_code`) come from `000-man-core-bootstrap.xml`, whose changesets are convergence guards against the `dcre-infra` seed and the sibling M-services, not ownership claims. v1 does not create the `mandate` projection at all. `man_outbound` is MRW-owned and never shipped here. Batch metadata lives in `MPX_BATCH_`-prefixed tables (`dcre.batch.table-prefix`, read by platform-batch `BatchJdbcConfig`) via a Liquibase-owned copy of the Spring Batch 6 DDL in pure typed XML (`002-batch-metadata.xml`, `EXIT_MESSAGE` widened to TEXT); Batch never auto-initializes its own schema. Liquibase history on the shared DB is per-service: `mpx_databasechangelog` / `mpx_databasechangeloglock`.

## Prerequisites

- Java 25: `.sdkmanrc` pins `java=25-tem` (`sdk env`); `build.gradle` sets source and target compatibility 25
- Gradle 9.5.1 via the included wrapper (`gradle/wrapper/gradle-wrapper.properties`)
- Docker (Testcontainers CockroachDB for tests, image build for deployment)
- Platform libs in Maven Local: `za.co.fnb.dcre:platform-persistence:0.1.0` and `za.co.fnb.dcre:platform-batch:0.1.0`

## Quickstart

```bash
# 1) Publish the platform libs to Maven Local (once), in dependency order:
#    dcre-platform-model -> dcre-platform-files -> dcre-platform-batch; dcre-platform-persistence standalone.
#    In each platform repo clone:
./gradlew publishToMavenLocal

# 2) Build and test (Docker required; no .env needed, dev defaults are committed)
./gradlew test

# 3) Local one-shot run against a local CockroachDB (defaults target localhost:26257/dcre_man)
./gradlew bootJar
java -jar build/libs/mpx-2.0.jar \
  'arrival.id=<uuid>,java.lang.String,true' \
  'input.file=/path/to/reply.xml,java.lang.String,false' \
  'original.name=FNBCC01_OUTMSG-1_PBSR.xml,java.lang.String,false'
```

## Configuration

Env over committed dev defaults (`application.yml`, the only profile); precedence: yml default < environment. The table is the documented set, not a closed total: Spring Boot relaxed binding lets any property be overridden by its derived environment variable name (for example `dcre.batch.table-prefix` as `DCRE_BATCH_TABLEPREFIX`).

| Env | Default | Purpose |
|---|---|---|
| `DCRE_DB_URL` | `jdbc:postgresql://localhost:26257/dcre_man?sslmode=disable` | Shared mandates DB (CockroachDB) |
| `DCRE_DB_USER` | `root` | DB username |
| `DCRE_DB_PASSWORD` | (empty) | DB password |
| `DCRE_AGTOPS_DB_URL` | `jdbc:postgresql://localhost:26257/agt_ops?sslmode=disable` | Heartbeat datasource (`agt_ops.launch_intent` liveness stamp) |
| `DCRE_AGTOPS_DB_USER` | `root` | Heartbeat DB username |
| `DCRE_AGTOPS_DB_PASSWORD` | (empty) | Heartbeat DB password |
| `DCRE_EXCHANGE_ROOT` | `../../../../../../infra/dcre-infra/exchange` | Exchange root for the outcome seam |
| `JOB_NAME` | `local-mpx-<executionId>` | Set by AGT on the K8s Job; names the outcome seam file |

JobParameters: `arrival.id` (identifying, R-16), `input.file` and `original.name` (non-identifying; `original.name` becomes the `response_file` identity column). There is no `reply.type` parameter.

## Testing

```bash
./gradlew test   # needs Docker; runs JUnit, Testcontainers ITs and the Cucumber suite
./gradlew test --tests '*MpxReaderIT'   # one suite
```

Pinned test image: `cockroachdb/cockroach:v26.2.3` (`AbstractCrdbIT`). One Testcontainers CockroachDB container serves the whole module (`AbstractCrdbIT.CRDB`); suites are isolated by disjoint fixture keys, and the migration harness mints a virgin database per test.

- `MpxJobTest`: the real `mpxJob` through `JobOperator` on Testcontainers CockroachDB `v26.2.3`; a job launched with nothing but the file lands the PBSR leg, a rejected leg keeps its reason code, and a replay under a fresh job instance stays at one row.
- `MpxReaderIT`: reader proofs against real CRDB; the ingest lands in the one owned table, correlation persists every reply field, an unknown outbound identity is WARNed and excluded with nothing written, and a re-ingest is a zero-duplicate no-op preserving row identity.
- `DelayedAuthPbsrIT`: the PBSR-only delayed-authentication pair; two distinct file stems for one mandate both persist, while the same file twice stays at one row. Together they pin the idempotency key at exactly the right width.
- `MpxConvergenceStateIT`: convergence proofs against every arrival order of the two creators of `man_pbsr_resp`, not just an empty container; MPX first, MRG first (table and constraint already stand), a table standing without the constraint (which must gain it and keep `ON CONFLICT` working), and double-apply.
- `MandateReplyParserTest`: pure-parser proofs; mandatory correlation/verdict fields, optional `Rsn`, opportunistic `e2e` capture, and malformed replies failing the job.
- `ReaderServiceLegTest`: the leg is fixed to `man_pbsr_resp` at compile time.
- `CucumberSuiteTest`: business-readable BDD scenarios in `src/test/resources/features/mpx-acceptance-reader.feature` (correlated accept, reject reason, fail-closed exclusion, malformed reply).

## Local cluster deployment

```bash
./gradlew bootJar
docker build -t dcre-mpx:<version> .
kind load docker-image --name dcre-dev dcre-mpx:<version>
```

The image is `eclipse-temurin:25-jre-alpine` running `build/libs/mpx-2.0.jar`. AGT launches MPX as an ephemeral K8s Job in the mandates flow namespace (`agt.namespace-man`, env `AGT_NAMESPACE_MAN`, default `dcre-man`), named `man-mpx-<arrival id without dashes>`, whenever a `_PBSR` reply lands in a per-client `fint-resp-man/in` directory. The image comes from AGT's `AGT_MPX_IMAGE` (empty default, which leaves the stage launch-disabled). Job arguments: `arrival.id=<uuid>` (identifying), `input.file` and `original.name` (non-identifying). Pod env: `DCRE_DB_URL` (AGT `AGT_MAN_SERVICE_DB_URL`, default `jdbc:postgresql://crdb.dcre.svc.cluster.local:26257/dcre_man?sslmode=disable`), `DCRE_EXCHANGE_ROOT=/exchange` (the `dcre-exchange` PVC), `JOB_NAME`, `DCRE_AGTOPS_DB_URL` and `DCRE_AGTOPS_DB_USER` (read from AGT on origin/dev, checked 2026-09-28). Image versions are set fleet-wide by dcre-infra `scripts/switch-version.sh`, which exports the mandates stages only from the 2.3 release line (checked 2026-09-28); the cluster itself is defined in dcre-infra. Releases are digits-only 3-component SemVer tags, uniform across the fleet.

## Related repositories

The complete, current list of live DCRE repositories (stage services, orchestrator, platform libraries, infra and tooling) lives in one place: the [DCRE design register README](https://github.com/sean-huni/dcre-design-register#repositories). Deprecated and archived repositories are deliberately absent from it. This README does not copy that list, so it cannot drift.

- Design register: https://github.com/sean-huni/dcre-design-register (start at `docs/specs/DESIGN-REGISTER.md`; the diagrams in `docs/diagrams/` are the specification)
