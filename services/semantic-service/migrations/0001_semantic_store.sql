CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS semantic_documents (
    app_id UUID PRIMARY KEY,
    content_hash CHAR(64) NOT NULL,
    content TEXT NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS embedding_models (
    model_version TEXT PRIMARY KEY,
    model_key TEXT NOT NULL,
    hf_repository TEXT NOT NULL,
    hf_revision CHAR(40) NOT NULL,
    dimensions INTEGER NOT NULL CHECK (dimensions > 0),
    query_prefix TEXT NOT NULL DEFAULT '',
    passage_prefix TEXT NOT NULL DEFAULT '',
    artifact_path TEXT,
    dataset_hash CHAR(64),
    training_config JSONB,
    lifecycle_state TEXT NOT NULL DEFAULT 'registered'
        CHECK (lifecycle_state IN ('registered', 'selected', 'active', 'retired', 'failed')),
    active BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    activated_at TIMESTAMPTZ,
    UNIQUE (model_key, hf_revision, dataset_hash)
);

CREATE TABLE IF NOT EXISTS software_embeddings (
    app_id UUID NOT NULL REFERENCES semantic_documents(app_id) ON DELETE CASCADE,
    model_version TEXT NOT NULL REFERENCES embedding_models(model_version) ON DELETE CASCADE,
    content_hash CHAR(64) NOT NULL,
    embedding VECTOR NOT NULL,
    generated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (app_id, model_version)
);

CREATE TABLE IF NOT EXISTS embedding_jobs (
    id BIGSERIAL PRIMARY KEY,
    app_id UUID NOT NULL REFERENCES semantic_documents(app_id) ON DELETE CASCADE,
    model_version TEXT NOT NULL REFERENCES embedding_models(model_version) ON DELETE CASCADE,
    content_hash CHAR(64) NOT NULL,
    status TEXT NOT NULL DEFAULT 'queued'
        CHECK (status IN ('queued', 'processing', 'completed', 'failed')),
    attempts INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    lease_owner TEXT,
    lease_until TIMESTAMPTZ,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (app_id, model_version, content_hash)
);

CREATE INDEX IF NOT EXISTS ix_embedding_jobs_claim
    ON embedding_jobs (model_version, status, available_at, id);

CREATE TABLE IF NOT EXISTS semantic_index_state (
    model_version TEXT PRIMARY KEY REFERENCES embedding_models(model_version) ON DELETE CASCADE,
    index_version TEXT NOT NULL,
    snapshot_hash CHAR(64) NOT NULL,
    expected_documents INTEGER NOT NULL DEFAULT 0,
    indexed_documents INTEGER NOT NULL DEFAULT 0,
    complete BOOLEAN NOT NULL DEFAULT FALSE,
    built_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    activated_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS benchmark_runs (
    id UUID PRIMARY KEY,
    dataset_hash CHAR(64) NOT NULL,
    seed INTEGER NOT NULL,
    configuration JSONB NOT NULL,
    metrics JSONB NOT NULL,
    selected_model_version TEXT,
    report_json_path TEXT,
    report_csv_path TEXT,
    report_markdown_path TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO embedding_models (
    model_version, model_key, hf_repository, hf_revision, dimensions,
    query_prefix, passage_prefix, lifecycle_state
) VALUES
(
    'paraphrase-multilingual-MiniLM-L12-v2@e8f8c211226b894fcb81acc59f3b34ba3efd5f42:zero-shot',
    'paraphrase-multilingual-MiniLM-L12-v2',
    'sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2',
    'e8f8c211226b894fcb81acc59f3b34ba3efd5f42',
    384, '', '', 'registered'
),
(
    'multilingual-e5-base@d128750597153bb5987e10b1c3493a34e5a4502a:zero-shot',
    'multilingual-e5-base',
    'intfloat/multilingual-e5-base',
    'd128750597153bb5987e10b1c3493a34e5a4502a',
    768, 'query: ', 'passage: ', 'selected'
),
(
    'bge-m3@5617a9f61b028005a4858fdac845db406aefb181:zero-shot',
    'bge-m3',
    'BAAI/bge-m3',
    '5617a9f61b028005a4858fdac845db406aefb181',
    1024, '', '', 'registered'
)
ON CONFLICT (model_version) DO NOTHING;

CREATE INDEX IF NOT EXISTS ix_embeddings_77e7db8d29ffe398_hnsw
    ON software_embeddings
    USING hnsw ((embedding::vector(384)) vector_cosine_ops)
    WHERE model_version =
      'paraphrase-multilingual-MiniLM-L12-v2@e8f8c211226b894fcb81acc59f3b34ba3efd5f42:zero-shot';

CREATE INDEX IF NOT EXISTS ix_embeddings_76688c11ad80efe9_hnsw
    ON software_embeddings
    USING hnsw ((embedding::vector(768)) vector_cosine_ops)
    WHERE model_version =
      'multilingual-e5-base@d128750597153bb5987e10b1c3493a34e5a4502a:zero-shot';

CREATE INDEX IF NOT EXISTS ix_embeddings_c9aff84bb59b7fb2_hnsw
    ON software_embeddings
    USING hnsw ((embedding::vector(1024)) vector_cosine_ops)
    WHERE model_version =
      'bge-m3@5617a9f61b028005a4858fdac845db406aefb181:zero-shot';
