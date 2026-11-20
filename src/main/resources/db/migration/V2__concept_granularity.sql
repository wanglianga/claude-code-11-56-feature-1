-- =====================================================================
-- Concept granularity evolution
--
-- When a vendor splits one coarse fault code (e.g. "hydraulic pressure
-- abnormal") into several fine codes, neither re-labelling old records as
-- the fine codes nor folding every fine code back into the old coarse code
-- is faithful. These tables keep the evolution explicit:
--   * concept_hierarchy      versioned parent/child concepts with firmware
--                            applicability, reversible/irreversible aggregation
--                            and the engineering basis for the relation
--   * concept_bridge_matrix  engineer-approved bridges that roll fine and
--                            coarse concepts onto one comparable coarse idea
--                            (stable reports, but hides intra-fine changes)
--   * unattributed_alarm     alarms whose firmware evidence is missing; they
--                            stay "pending attribution" and never enter
--                            automatic statistics
--   * work_order_concept_link  reopened/historical work orders keep their
--                            original diagnosis and additionally reference
--                            the new granularity explanation
--
-- Only APPROVED hierarchy edges and bridge rows may feed automatic
-- statistics. A fine code without an approved engineering definition is
-- retained but excluded from every aggregated metric.
-- =====================================================================

CREATE TABLE concept_hierarchy (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    parent_concept_id      UUID NOT NULL REFERENCES concept (id),
    child_concept_id       UUID NOT NULL REFERENCES concept (id),
    relationship_type      VARCHAR(16) NOT NULL CHECK (relationship_type IN ('SPLIT', 'MERGE', 'REFINE')),
    aggregation_kind       VARCHAR(16) NOT NULL CHECK (aggregation_kind IN ('REVERSIBLE', 'IRREVERSIBLE')),
    scope_model_id         UUID REFERENCES turbine_model (id),
    firmware_from          VARCHAR(64),
    firmware_to            VARCHAR(64),
    firmware_from_seq      INTEGER,
    firmware_to_seq        INTEGER,
    engineering_basis      TEXT NOT NULL,
    status                 VARCHAR(16) NOT NULL DEFAULT 'PROPOSED'
                               CHECK (status IN ('PROPOSED', 'APPROVED', 'RETIRED')),
    valid_from             TIMESTAMPTZ NOT NULL,
    valid_to               TIMESTAMPTZ,
    approved_by            VARCHAR(128),
    approval_ref           VARCHAR(200),
    approved_at            TIMESTAMPTZ,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_concept_hierarchy UNIQUE (parent_concept_id, child_concept_id,
                                            scope_model_id, firmware_from),
    CONSTRAINT ck_hierarchy_distinct CHECK (parent_concept_id <> child_concept_id),
    CONSTRAINT ck_hierarchy_fw_seq CHECK (
        (firmware_from IS NULL) = (firmware_from_seq IS NULL)
        AND (firmware_to IS NULL) = (firmware_to_seq IS NULL)
    ),
    CONSTRAINT ck_hierarchy_validity CHECK (valid_to IS NULL OR valid_to > valid_from)
);
CREATE INDEX idx_hierarchy_parent ON concept_hierarchy (parent_concept_id, status);
CREATE INDEX idx_hierarchy_child ON concept_hierarchy (child_concept_id, status);

-- ---------------------------------------------------------------------
-- Engineer-approved bridging matrix. Each row says "records of
-- source_concept under the given scope may be reported together under
-- target_concept at coarse granularity". A weight is kept for proportional
-- allocations; absence of a bridge row means the concept is excluded from
-- bridged reports rather than guessed.
-- ---------------------------------------------------------------------
CREATE TABLE concept_bridge_matrix (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    matrix_code         VARCHAR(64) NOT NULL,
    source_concept_id   UUID NOT NULL REFERENCES concept (id),
    target_concept_id   UUID NOT NULL REFERENCES concept (id),
    scope_model_id      UUID REFERENCES turbine_model (id),
    firmware_from       VARCHAR(64),
    firmware_to         VARCHAR(64),
    firmware_from_seq   INTEGER,
    firmware_to_seq     INTEGER,
    weight              NUMERIC(6, 3) NOT NULL DEFAULT 1.0,
    engineering_basis   TEXT NOT NULL,
    status              VARCHAR(16) NOT NULL DEFAULT 'PROPOSED'
                            CHECK (status IN ('PROPOSED', 'APPROVED', 'RETIRED')),
    valid_from          TIMESTAMPTZ NOT NULL,
    valid_to            TIMESTAMPTZ,
    approved_by         VARCHAR(128),
    approval_ref        VARCHAR(200),
    approved_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_bridge_row UNIQUE (source_concept_id, target_concept_id,
                                     scope_model_id, firmware_from),
    CONSTRAINT ck_bridge_distinct CHECK (source_concept_id <> target_concept_id),
    CONSTRAINT ck_bridge_weight CHECK (weight > 0),
    CONSTRAINT ck_bridge_fw_seq CHECK (
        (firmware_from IS NULL) = (firmware_from_seq IS NULL)
        AND (firmware_to IS NULL) = (firmware_to_seq IS NULL)
    ),
    CONSTRAINT ck_bridge_validity CHECK (valid_to IS NULL OR valid_to > valid_from)
);
CREATE INDEX idx_bridge_source ON concept_bridge_matrix (source_concept_id, status);
CREATE INDEX idx_bridge_target ON concept_bridge_matrix (target_concept_id, status);

-- ---------------------------------------------------------------------
-- Alarms whose firmware evidence is missing. They are surfaced as pending
-- attribution only and are excluded from automatic trend statistics.
-- ---------------------------------------------------------------------
CREATE TABLE unattributed_alarm (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    alarm_id            UUID NOT NULL UNIQUE REFERENCES alarm (id),
    reason_code         VARCHAR(48) NOT NULL DEFAULT 'FIRMWARE_EVIDENCE_MISSING'
                            CHECK (reason_code IN ('FIRMWARE_EVIDENCE_MISSING', 'AMBIGUOUS_FIRMWARE_WINDOW')),
    raw_firmware_hint   VARCHAR(128),
    note                TEXT,
    detected_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at         TIMESTAMPTZ,
    resolution_note     TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_unattributed_open ON unattributed_alarm (resolved_at);

-- ---------------------------------------------------------------------
-- Reopened/historical work orders keep their original diagnosis but may be
-- associated with the new-granularity concept explanation. This never
-- rewrites the work order's original term or its historical findings.
-- ---------------------------------------------------------------------
CREATE TABLE work_order_concept_link (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    work_order_id   UUID NOT NULL REFERENCES work_order (id),
    concept_id      UUID NOT NULL REFERENCES concept (id),
    link_type       VARCHAR(32) NOT NULL DEFAULT 'REOPENED_GRANULARITY_NOTE'
                        CHECK (link_type IN ('REOPENED_GRANULARITY_NOTE', 'HISTORICAL_REFERENCE')),
    note            TEXT NOT NULL,
    created_by      VARCHAR(128) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_wo_concept_link UNIQUE (work_order_id, concept_id, link_type)
);
CREATE INDEX idx_wo_link_wo ON work_order_concept_link (work_order_id);

-- ---------------------------------------------------------------------
-- Release snapshots freeze the granularity evolution exactly as approved.
-- Snapshot-mode comparison reads only these rows.
-- ---------------------------------------------------------------------
CREATE TABLE release_concept_hierarchy (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    release_id           UUID NOT NULL REFERENCES release (id),
    parent_concept_code  VARCHAR(64) NOT NULL,
    child_concept_code   VARCHAR(64) NOT NULL,
    relationship_type    VARCHAR(16) NOT NULL,
    aggregation_kind     VARCHAR(16) NOT NULL,
    model_code           VARCHAR(64),
    firmware_from        VARCHAR(64),
    firmware_to          VARCHAR(64),
    firmware_from_seq    INTEGER,
    firmware_to_seq      INTEGER,
    engineering_basis    TEXT NOT NULL,
    status               VARCHAR(16) NOT NULL,
    valid_from           TIMESTAMPTZ NOT NULL,
    valid_to             TIMESTAMPTZ,
    approved_by          VARCHAR(128),
    approval_ref         VARCHAR(200),
    approved_at          TIMESTAMPTZ
);
CREATE INDEX idx_release_hierarchy ON release_concept_hierarchy (release_id);

CREATE TABLE release_concept_bridge (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    release_id           UUID NOT NULL REFERENCES release (id),
    matrix_code          VARCHAR(64) NOT NULL,
    source_concept_code  VARCHAR(64) NOT NULL,
    target_concept_code  VARCHAR(64) NOT NULL,
    model_code           VARCHAR(64),
    firmware_from        VARCHAR(64),
    firmware_to          VARCHAR(64),
    firmware_from_seq    INTEGER,
    firmware_to_seq      INTEGER,
    weight               NUMERIC(6, 3) NOT NULL,
    engineering_basis    TEXT NOT NULL,
    status               VARCHAR(16) NOT NULL,
    valid_from           TIMESTAMPTZ NOT NULL,
    valid_to             TIMESTAMPTZ,
    approved_by          VARCHAR(128),
    approval_ref         VARCHAR(200),
    approved_at          TIMESTAMPTZ
);
CREATE INDEX idx_release_bridge ON release_concept_bridge (release_id);
