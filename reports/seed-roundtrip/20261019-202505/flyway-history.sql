CREATE TABLE flyway_schema_history (
  installed_rank integer NOT NULL,
  version varchar(50),
  description varchar(200) NOT NULL,
  type varchar(20) NOT NULL,
  script varchar(1000) NOT NULL,
  checksum integer,
  installed_by varchar(100) NOT NULL,
  installed_on timestamp NOT NULL DEFAULT now(),
  execution_time integer NOT NULL,
  success boolean NOT NULL,
  PRIMARY KEY (installed_rank));
INSERT INTO flyway_schema_history VALUES (1, '1', 'seed roundtrip', 'SQL', 'V1__init_core_schema.sql', NULL, 'seedtest', now(), 0, true);
INSERT INTO flyway_schema_history VALUES (2, '2', 'seed roundtrip', 'SQL', 'V2__core_invariants.sql', NULL, 'seedtest', now(), 0, true);
INSERT INTO flyway_schema_history VALUES (3, '3', 'seed roundtrip', 'SQL', 'V3__outbox_lease_and_public_barrier.sql', NULL, 'seedtest', now(), 0, true);
INSERT INTO flyway_schema_history VALUES (4, '4', 'seed roundtrip', 'SQL', 'V4__login_throttle.sql', NULL, 'seedtest', now(), 0, true);
INSERT INTO flyway_schema_history VALUES (5, '5', 'seed roundtrip', 'SQL', 'V5__title_similarity_index.sql', NULL, 'seedtest', now(), 0, true);
INSERT INTO flyway_schema_history VALUES (6, '6', 'seed roundtrip', 'SQL', 'V6__runtime_grants.sql', NULL, 'seedtest', now(), 0, true);
INSERT INTO flyway_schema_history VALUES (7, '7', 'seed roundtrip', 'SQL', 'V7__year_precision_unknown.sql', NULL, 'seedtest', now(), 0, true);
INSERT INTO flyway_schema_history VALUES (8, '8', 'seed roundtrip', 'SQL', 'V8__crossref_citation_fields.sql', NULL, 'seedtest', now(), 0, true);
INSERT INTO flyway_schema_history VALUES (9, '9', 'seed roundtrip', 'SQL', 'V9__candidate_rebased_status.sql', NULL, 'seedtest', now(), 0, true);
INSERT INTO flyway_schema_history VALUES (10, '10', 'seed roundtrip', 'SQL', 'V10__achievement_topic_assignment.sql', NULL, 'seedtest', now(), 0, true);
INSERT INTO flyway_schema_history VALUES (11, '11', 'seed roundtrip', 'SQL', 'V11__source_display_license.sql', NULL, 'seedtest', now(), 0, true);
INSERT INTO flyway_schema_history VALUES (12, '12', 'seed roundtrip', 'SQL', 'V12__notifications.sql', NULL, 'seedtest', now(), 0, true);
INSERT INTO flyway_schema_history VALUES (13, '13', 'seed roundtrip', 'SQL', 'V13__achievement_integrity_notice.sql', NULL, 'seedtest', now(), 0, true);
INSERT INTO flyway_schema_history VALUES (14, '14', 'seed roundtrip', 'SQL', 'V14__scholar_profile.sql', NULL, 'seedtest', now(), 0, true);
INSERT INTO flyway_schema_history VALUES (15, '15', 'seed roundtrip', 'SQL', 'V15__submission.sql', NULL, 'seedtest', now(), 0, true);
INSERT INTO flyway_schema_history VALUES (16, '16', 'seed roundtrip', 'SQL', 'V16__citation_network.sql', NULL, 'seedtest', now(), 0, true);
INSERT INTO flyway_schema_history VALUES (17, '17', 'seed roundtrip', 'SQL', 'V17__submission_publish_confirmation_backfill.sql', NULL, 'seedtest', now(), 0, true);
INSERT INTO flyway_schema_history VALUES (18, '18', 'seed roundtrip', 'SQL', 'V18__account_password_selfservice.sql', NULL, 'seedtest', now(), 0, true);
INSERT INTO flyway_schema_history VALUES (19, '19', 'seed roundtrip', 'SQL', 'V19__related_recommendation_indexes.sql', NULL, 'seedtest', now(), 0, true);
INSERT INTO flyway_schema_history VALUES (20, '20', 'seed roundtrip', 'SQL', 'V20__notification_archive_and_announcement.sql', NULL, 'seedtest', now(), 0, true);
INSERT INTO flyway_schema_history VALUES (21, '21', 'seed roundtrip', 'SQL', 'V21__account_closure_request.sql', NULL, 'seedtest', now(), 0, true);
INSERT INTO flyway_schema_history VALUES (22, '22', 'seed roundtrip', 'SQL', 'V22__scholar_favorite.sql', NULL, 'seedtest', now(), 0, true);
