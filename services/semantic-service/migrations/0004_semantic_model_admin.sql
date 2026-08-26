CREATE TABLE IF NOT EXISTS semantic_model_artifacts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    hf_repository TEXT NOT NULL,
    requested_revision TEXT,
    resolved_revision CHAR(40) NOT NULL,
    display_name TEXT NOT NULL,
    local_path TEXT,
    artifact_state TEXT NOT NULL DEFAULT 'downloading'
        CHECK (artifact_state IN (
            'downloading', 'validating', 'ready', 'incompatible', 'failed', 'deleted'
        )),
    artifact_bytes BIGINT NOT NULL DEFAULT 0 CHECK (artifact_bytes >= 0),
    manifest_digest CHAR(64),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    validation_message TEXT,
    dimensions INTEGER CHECK (dimensions BETWEEN 1 AND 2000),
    query_prefix TEXT NOT NULL DEFAULT '',
    passage_prefix TEXT NOT NULL DEFAULT '',
    minimum_similarity DOUBLE PRECISION NOT NULL DEFAULT 0.0
        CHECK (minimum_similarity BETWEEN -1 AND 1),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    downloaded_at TIMESTAMPTZ,
    validated_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ,
    UNIQUE (hf_repository, resolved_revision)
);

ALTER TABLE embedding_models
    ADD COLUMN IF NOT EXISTS artifact_id UUID
        REFERENCES semantic_model_artifacts(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS minimum_similarity DOUBLE PRECISION NOT NULL DEFAULT 0.0
        CHECK (minimum_similarity BETWEEN -1 AND 1),
    ADD COLUMN IF NOT EXISTS deployment_state TEXT NOT NULL DEFAULT 'not_prepared'
        CHECK (deployment_state IN (
            'not_prepared', 'preparing', 'ready', 'active', 'stale', 'failed'
        ));

CREATE UNIQUE INDEX IF NOT EXISTS ux_embedding_models_artifact
    ON embedding_models (artifact_id)
    WHERE artifact_id IS NOT NULL;

INSERT INTO semantic_model_artifacts (
    hf_repository,
    requested_revision,
    resolved_revision,
    display_name,
    local_path,
    artifact_state,
    metadata,
    dimensions,
    query_prefix,
    passage_prefix,
    minimum_similarity,
    downloaded_at,
    validated_at
)
SELECT
    m.hf_repository,
    m.hf_revision,
    m.hf_revision,
    m.model_key,
    m.artifact_path,
    'ready',
    jsonb_build_object(
        'source', 'registry',
        'reconciled', TRUE,
        'libraryName', 'sentence-transformers'
    ),
    m.dimensions,
    m.query_prefix,
    m.passage_prefix,
    CASE
        WHEN m.model_key = 'multilingual-e5-base' THEN 0.82
        ELSE 0.0
    END,
    now(),
    now()
FROM embedding_models m
WHERE m.model_version LIKE '%:zero-shot'
ON CONFLICT (hf_repository, resolved_revision) DO NOTHING;

UPDATE embedding_models m
SET artifact_id = a.id,
    minimum_similarity = a.minimum_similarity,
    deployment_state = CASE
        WHEN m.active THEN 'active'
        WHEN EXISTS (
            SELECT 1
            FROM semantic_index_state s
            WHERE s.model_version = m.model_version
              AND s.complete = TRUE
        ) THEN 'ready'
        ELSE 'not_prepared'
    END
FROM semantic_model_artifacts a
WHERE m.model_version LIKE '%:zero-shot'
  AND a.hf_repository = m.hf_repository
  AND a.resolved_revision = m.hf_revision
  AND m.artifact_id IS NULL;

CREATE TABLE IF NOT EXISTS semantic_operations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    operation_kind TEXT NOT NULL
        CHECK (operation_kind IN ('download', 'benchmark', 'prepare', 'activate', 'delete')),
    status TEXT NOT NULL DEFAULT 'queued'
        CHECK (status IN (
            'queued', 'running', 'cancel_requested', 'cancelled', 'succeeded', 'failed'
        )),
    phase TEXT NOT NULL DEFAULT 'queued',
    model_id UUID REFERENCES semantic_model_artifacts(id) ON DELETE SET NULL,
    model_version TEXT,
    repository TEXT,
    resolved_revision CHAR(40),
    progress_current BIGINT NOT NULL DEFAULT 0 CHECK (progress_current >= 0),
    progress_total BIGINT NOT NULL DEFAULT 0 CHECK (progress_total >= 0),
    progress_unit TEXT NOT NULL DEFAULT 'items',
    safe_message TEXT,
    error_code TEXT,
    request_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    result_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    actor TEXT NOT NULL DEFAULT 'admin',
    idempotency_key TEXT,
    attempts INTEGER NOT NULL DEFAULT 0,
    lease_owner TEXT,
    lease_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_semantic_operations_idempotency
    ON semantic_operations (idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS ix_semantic_operations_claim
    ON semantic_operations (status, created_at)
    WHERE status IN ('queued', 'running', 'cancel_requested');

ALTER TABLE benchmark_runs
    ADD COLUMN IF NOT EXISTS operation_id UUID
        REFERENCES semantic_operations(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS model_ids UUID[] NOT NULL DEFAULT '{}'::uuid[],
    ADD COLUMN IF NOT EXISTS scope TEXT NOT NULL DEFAULT 'legacy'
        CHECK (scope IN ('legacy', 'smoke', 'full')),
    ADD COLUMN IF NOT EXISTS hardware_fingerprint TEXT,
    ADD COLUMN IF NOT EXISTS document_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS query_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS metrics_schema_version INTEGER NOT NULL DEFAULT 1;

UPDATE benchmark_runs
SET scope = CASE
        WHEN COALESCE((configuration ->> 'smoke')::boolean, FALSE) THEN 'smoke'
        ELSE 'legacy'
    END
WHERE scope = 'legacy';
