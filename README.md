# dcre-mpx

PBSR response reader for DCRE Mandates: ingests one pain.012 PBSR acceptance leg into `man_pbsr_resp`, one verdict row per reply file.

## What it does

MPX is the final-acceptance leg of the mandates response flow (`MIX | MSX | MPX -> mnd_ext_status -> MRG`). Fintegrate (simulated by dcre-infra `fint_sim_reply.py --mandate`) drops a reply file into a per-client `fint-resp-man/in` exchange directory; AGT selects the reader by the `_PBSR` filename token and launches MPX as a short-lived Kubernetes Job. MPX parses the reply (one `<OrgnlMsgId>` plus `<MndtReqId>`, `<MndtId>`, `<MndtSts>` and an optional `<Rsn>`, [SYNTHETIC-CONTRACT R-35/A-60] shape), correlates it fail-closed to the MRW outbound registry, and writes exactly one `man_pbsr_resp` row. Replaying the same file is a no-op via `INSERT ... ON CONFLICT (response_file, mndt_req_id) DO NOTHING`.

**Launch contract (CHANGED, SCRUM-91).** MPX takes `input.file` and `original.name` and **NO `reply.type` parameter**. The leg is a compile-time property of the service, not a launch argument: MPX only ever writes `man_pbsr_resp`. The merged three-table reader that selected its table from `reply.type` was MAR's shape, and splitting it into three single-leg readers (MIX/MSX/MPX, mirroring the collections IXR/SXR/PXR fleet) is what this refactor exists to do. A DAG cannot mis-route a leg it cannot name.

**Delayed debtor authentication, the one behavior only this leg has.** The debtor may authenticate late, so a SECOND PBSR arrives for the SAME mandate under a distinct file stem (`<stem>-AUTH_PBSR.xml`). BOTH rows must persist. The business identity is `(response_file, mndt_req_id)`, so a distinct file is a distinct row, neither arrival suppresses the other, and the precedence view picks the later one. A key narrower than that tuple (say `mndt_req_id` alone) would silently swallow the authentication.

## Architecture and principles

Spring Boot 4.1.0 / Spring Batch 6 / Java 25 on CockroachDB v26.2.3 (PostgreSQL driver). An ephemeral batch job, not a server: `ExitCodeMain` (platform-batch) wires the Batch outcome into the JVM exit code (R-34).

- **SOLID, 3-tier, layer-first packages**: `ReaderTasklet` is a thin entry adapter (no SQL, no parsing) that reads `input.file` and calls one business-tier method; `ReaderService` parses, correlates and writes; persistence happens only through `data/repo/ManRespRepo` (Spring Data JDBC, `ManPbsrRespEntity` extends the platform `BaseEntity`). Packages: `config`, `service`, `domain`, `data/model`, `data/repo`.
- **One literal names the write target**: `ManPbsrRespEntity.TABLE` is read by the `@Table` mapping, by the guarded insert's native `@Query`, and by `ReaderService.TARGET_TABLE`. There is no second table literal to drift out of step, so the leg assertion and the actual write target cannot disagree. On a live `dcre_man` all three leg tables exist, which is exactly what makes a drifted literal a SILENT cross-leg write rather than a loud failure.
- **12FactorApp Alignment: https://12factor.net/**: config strictly from the environment over committed working dev defaults in `application.yml` (a clean clone runs with no `.env` at all), stateless one-shot process, the shared CockroachDB as an attached backing resource.
- **Fail-closed correlation (SCRUM-60 canon)**: the reply's `OrgnlMsgId` must resolve to a known `man_outbound.out_msg_id` or the reply is WARNed and EXCLUDED. Never a guessed family fallback: a known outbound identity is the only evidence the route is real. MPX stores nothing from `man_outbound`; every persisted column comes from the reply itself.
- **Idempotent restart semantics**: `ManRespRepo.insertGuarded` is a native `INSERT ... ON CONFLICT (response_file, mndt_req_id) DO NOTHING` on the FULL business identity (CRDB `UPSERT` arbitrates on the PK only, so a business key needs `ON CONFLICT`). Statuses are truth-on-first-arrival for a given file, so a re-run never clobbers. The write commits in its OWN `REQUIRES_NEW` transaction wrapped by `CrdbRetry`, so a 40001 abort retries in a fresh transaction instead of poisoning the surrounding one.
- **40001 at the step boundary**: `readerStep` registers the shared `CrdbRetryExceptionHandler` (platform-batch) so commit-time serialization aborts retry instead of failing the job.
- **Outcome seam (R-35)**: on `COMPLETED`, `OutcomeSeamListener` writes `BUSINESS_ACCEPTED` to `<exchange-root>/outcomes/<JOB_NAME>`. A non-COMPLETED execution writes nothing; the exit code and the K8s Failed condition are the witnesses, and AGT treats absence as never-success (R-33).
- **Ingest return value**: `ReaderService.ingest` returns the rows this run INSERTED, surfaced in the JobExecutionContext as `"rows"`. `1` means first arrival. `0` conflates two distinct outcomes, a fail-closed exclusion and a replay no-op, and is NOT evidence of a missing row; only the `reason=UNKNOWN_OUTBOUND_MSG` WARN distinguishes them. Neither outcome fails the job.

### Data

`man_pbsr_resp` (Liquibase `db/changelog/2026/07/001-man-pbsr-resp.xml`): `response_file`, `orgnl_msg_id`, `mndt_id`, `mndt_req_id`, `e2e` (nullable, the current synthetic contract carries no `EndToEndId`), `status`, `reason` (nullable), plus `BaseEntity` columns (`version`, `created_at`, `updated_at`); `UNIQUE (response_file, mndt_req_id)`.

The table and its unique constraint are SEPARATE changesets, each guarded on the schema state it transforms (`tableExists` and `indexExists`). The table changed owning service (`mar -> mpx`) and changelog filename, so on an already-migrated database its identity is new while the table already exists: the `MARK_RAN` guard converges it without re-executing DDL. Folding the constraint into the same changeset would MARK_RAN it too on a half-migrated database, silently leaving the runtime `ON CONFLICT` with nothing to arbitrate on.

Shared-core shapes (`account`, `account_type`, `mandate`, ...) come from `000-man-core-bootstrap.xml`, whose changesets are bootstrap guards, not ownership claims. `man_outbound` is MRW-owned and never shipped here. Batch metadata lives in `MPX_BATCH_`-prefixed tables (`dcre.batch.table-prefix`, read by platform-batch `BatchJdbcConfig`) via a Liquibase-owned copy of the Spring Batch 6 DDL (`002-batch-metadata.xml` loading `batch-metadata-mpx.sql`, `EXIT_MESSAGE` widened to TEXT); Batch never auto-initializes its own schema. Liquibase history on the shared DB is per-service: `mpx_databasechangelog` / `mpx_databasechangeloglock`.

## Prerequisites

- Java 25 (Gradle toolchain; wrapper included)
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

Env over committed dev defaults (`application.yml`); precedence: yml default < environment.

| Env | Default | Purpose |
|---|---|---|
| `DCRE_DB_URL` | `jdbc:postgresql://localhost:26257/dcre_man?sslmode=disable` | Shared mandates DB (CockroachDB) |
| `DCRE_DB_USER` | `root` | DB username |
| `DCRE_DB_PASSWORD` | (empty) | DB password |
| `DCRE_AGTOPS_DB_URL` | `jdbc:postgresql://localhost:26257/agt_ops?sslmode=disable` | Heartbeat datasource (`agt_ops.launch_intent` liveness stamp) |
| `DCRE_AGTOPS_DB_USER` | `root` | Heartbeat DB username |
| `DCRE_AGTOPS_DB_PASSWORD` | (empty) | Heartbeat DB password |
| `DCRE_EXCHANGE_ROOT` | `../../../../../infra/dcre-infra/exchange` | Exchange root for the outcome seam |
| `JOB_NAME` | `local-mpx-<executionId>` | Set by AGT on the K8s Job; names the outcome seam file |

JobParameters: `arrival.id` (identifying, R-16), `input.file` and `original.name` (non-identifying; `original.name` becomes the `response_file` identity column). There is no `reply.type` parameter.

## Testing

```bash
./gradlew test   # needs Docker
```

One Testcontainers CockroachDB container serves the whole module (`AbstractCrdbIT.CRDB`); suites are isolated by disjoint fixture keys, and the migration harness mints a virgin database per test.

- `MpxJobTest`: the real `mpxJob` through `JobOperator` on Testcontainers CockroachDB `v26.2.3`; a job launched with nothing but the file lands the PBSR leg, a rejected leg keeps its reason code, and a replay under a fresh job instance stays at one row.
- `MpxReaderIT`: reader proofs against real CRDB; the ingest lands in the one owned table, correlation persists every reply field, an unknown outbound identity is WARNed and excluded with nothing written, and a re-ingest is a zero-duplicate no-op preserving row identity.
- `DelayedAuthPbsrIT`: the PBSR-only delayed-authentication pair; two distinct file stems for one mandate both persist, while the same file twice stays at one row. Together they pin the idempotency key at exactly the right width.
- `MpxLegacyStateIT`: migration proofs against legacy database states, not just fresh containers; fresh DB, the MAR legacy end-state, the half-migrated state (table without the unique constraint, which must gain it and keep `ON CONFLICT` working), and double-apply.
- `MandateReplyParserTest`: pure-parser proofs; mandatory correlation/verdict fields, optional `Rsn`, opportunistic `e2e` capture, and malformed replies failing the job.
- `ReaderServiceLegTest`: the leg is fixed to `man_pbsr_resp` at compile time.
- `CucumberSuiteTest`: business-readable BDD scenarios in `src/test/resources/features/mpx-acceptance-reader.feature` (correlated accept, reject reason, fail-closed exclusion, malformed reply).

## Local cluster deployment

```bash
./gradlew bootJar
docker build -t dcre-mpx:<version> .
kind load docker-image --name dcre-dev dcre-mpx:<version>
```

The image is `eclipse-temurin:25-jre-alpine`. AGT launches MPX as an ephemeral K8s Job in the `dcre` namespace whenever a `_PBSR` reply lands in a per-client `fint-resp-man/in` directory, resolving the image from its `AGT_MPX_IMAGE` env (managed fleet-wide by dcre-infra `scripts/switch-version.sh`, which exports the mandates stages only from the 2.3 release line). Releases are digits-only 3-component SemVer tags, uniform across the fleet.

## Related repositories

Mandates DAG: dcre-mrr, dcre-mrv, dcre-maf, dcre-mis, dcre-mir, dcre-mrw, dcre-mix, dcre-msx, dcre-mpx (this repo), dcre-mrg. Orchestrator: dcre-agt. Collections counterparts this fleet mirrors: dcre-ixr, dcre-sxr, dcre-pxr. Platform libs: dcre-platform-model, dcre-platform-files, dcre-platform-batch, dcre-platform-persistence. Support: dcre-infra, dcre-design-register, dcre-fixture-toolkit.
