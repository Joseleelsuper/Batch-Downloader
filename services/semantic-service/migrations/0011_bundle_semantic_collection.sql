CREATE TABLE bundle_semantic_documents (
    bundle_id UUID PRIMARY KEY,
    content_hash CHAR(64) NOT NULL,
    content TEXT NOT NULL,
    seen_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE bundle_embeddings (
    bundle_id UUID NOT NULL REFERENCES bundle_semantic_documents(bundle_id) ON DELETE CASCADE,
    model_version TEXT NOT NULL REFERENCES embedding_models(model_version) ON DELETE CASCADE,
    content_hash CHAR(64) NOT NULL,
    embedding VECTOR NOT NULL,
    PRIMARY KEY(bundle_id, model_version)
);
CREATE TABLE bundle_embedding_jobs (
    id BIGSERIAL PRIMARY KEY,
    bundle_id UUID NOT NULL REFERENCES bundle_semantic_documents(bundle_id) ON DELETE CASCADE,
    model_version TEXT NOT NULL REFERENCES embedding_models(model_version) ON DELETE CASCADE,
    content_hash CHAR(64) NOT NULL,
    status TEXT NOT NULL DEFAULT 'queued' CHECK(status IN ('queued','processing','completed','failed')),
    attempts INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    lease_until TIMESTAMPTZ,
    last_error TEXT,
    UNIQUE(bundle_id, model_version, content_hash)
);
CREATE INDEX ix_bundle_jobs_claim ON bundle_embedding_jobs(model_version, status, available_at, id);
CREATE TABLE bundle_index_state (
    model_version TEXT PRIMARY KEY REFERENCES embedding_models(model_version) ON DELETE CASCADE,
    source_revision BIGINT NOT NULL DEFAULT 0,
    index_version TEXT NOT NULL,
    expected_documents INTEGER NOT NULL,
    indexed_documents INTEGER NOT NULL,
    built_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
