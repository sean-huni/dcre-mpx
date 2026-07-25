@mpx
Feature: pain.012 mandate PBSR acceptance-leg ingestion
  MPX (the Mandate PBSR reader) picks up a pain.012 PBSR acceptance leg landed in
  fint-resp-man/in, correlates it to the MRW outbound registry, and stores one
  response row in man_pbsr_resp. The leg is fixed by the service (SCRUM-91), so no
  launch arg selects the table. Correlation is fail-closed: a reply whose original
  message id is not a known outbound identity is warned and excluded, never guessed
  onto a route.

  Scenario: A correlated PBSR acceptance leg lands in man_pbsr_resp
    Given the outbound message "OUT-100" for request "MREQ-100" is registered
    And a pain.012 PBSR reply file "fnbcc01_OUT-100_PBSR.xml" answering outbound "OUT-100" for request "MREQ-100" mandate "MND-100" with status "ACCP"
    When the MPX job ingests the reply file
    Then the job completes
    And 1 response row is stored in "man_pbsr_resp" for the reply file
    And the response row in "man_pbsr_resp" records status "ACCP"

  Scenario: A rejected PBSR leg keeps its reason code
    Given the outbound message "OUT-200" for request "MREQ-200" is registered
    And a pain.012 PBSR reply file "fnbcc01_OUT-200_PBSR.xml" answering outbound "OUT-200" for request "MREQ-200" mandate "MND-200" with status "RJCT" reason "AC04"
    When the MPX job ingests the reply file
    Then the job completes
    And the response row in "man_pbsr_resp" records status "RJCT" and reason "AC04"

  Scenario: An unknown outbound identity is excluded fail-closed
    Given a pain.012 PBSR reply file "fnbcc01_OUT-GHOST_PBSR.xml" answering outbound "OUT-GHOST" for request "MREQ-GHOST" mandate "MND-GHOST" with status "ACCP"
    When the MPX job ingests the reply file
    Then the job completes
    And 0 response rows are stored in "man_pbsr_resp" for the reply file

  Scenario: A malformed reply with no original message id fails the job
    Given a malformed pain.012 PBSR reply file "fnbcc01_broken_PBSR.xml" with no original message id
    When the MPX job ingests the reply file
    Then the job fails
