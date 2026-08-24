export type SemanticArtifactState =
  | 'ready'
  | 'incompatible'
  | 'failed'
  | 'deleted';

export type SemanticDeploymentState =
  | 'not_prepared'
  | 'preparing'
  | 'ready'
  | 'active'
  | 'stale'
  | 'failed';

export type SemanticOperationKind =
  | 'benchmark'
  | 'prepare'
  | 'activate'
  | 'delete';

export type SemanticOperationStatus =
  | 'queued'
  | 'running'
  | 'cancel_requested'
  | 'cancelled'
  | 'succeeded'
  | 'failed';

export interface SemanticIndexState {
  indexVersion?: string | null;
  snapshotHash?: string | null;
  expected: number;
  indexed: number;
  complete: boolean;
  builtAt?: string | null;
}

export interface SemanticBenchmarkMetric {
  modelId: string;
  modelKey: string;
  modelVersion: string;
  repository: string;
  variant: string;
  stage: string;
  scope: string;
  eligible: boolean;
  recommended?: boolean;
  ndcgAt10: number;
  mrrAt10: number;
  mapAt10: number;
  recallAt10: number;
  recallAt20: number;
  exactMrrAt1: number;
  p50Ms: number;
  p95Ms: number;
  p99Ms: number;
  throughputQps: number;
  embeddingBuildMs: number;
  hnswBuildMs: number;
  indexBuildMs: number;
  hnswRecallAt20: number;
  vectorBytes: number;
  hnswIndexBytes: number;
  indexBytes: number;
  rssBytes: number;
  vramBytes: number;
  totalScore: number;
  minimumSimilarity: number;
  loadMs?: number;
  warmupMs?: number;
  dimensions?: number;
  artifactBytes?: number;
}

export interface SemanticModel {
  id: string;
  displayName: string;
  repository: string;
  revision: string;
  artifactState: SemanticArtifactState;
  deploymentState: SemanticDeploymentState;
  artifactBytes: number;
  dimensions?: number | null;
  queryPrefix: string;
  passagePrefix: string;
  minimumSimilarity: number;
  metadata: Record<string, unknown>;
  validationMessage?: string | null;
  modelVersion?: string | null;
  active: boolean;
  createdAt: string;
  downloadedAt?: string | null;
  validatedAt?: string | null;
  activatedAt?: string | null;
  index: SemanticIndexState;
  lastBenchmark?: {
    id: string;
    datasetHash: string;
    scope: 'historical' | 'smoke' | 'full';
    hardwareFingerprint?: string | null;
    metric?: SemanticBenchmarkMetric | null;
    current: boolean;
    createdAt: string;
  } | null;
}

export interface SemanticOverview {
  service: string;
  searchReady: boolean;
  activeModel?: SemanticModel | null;
  disk: {
    modelBytes: number;
    freeBytes: number;
    totalBytes: number;
    reservedBytes: number;
    maximumModelBytes: number;
  };
  activeOperations: number;
}

export interface SemanticBenchmarkRun {
  id: string;
  datasetHash: string;
  seed: number;
  configuration: Record<string, unknown>;
  metrics: SemanticBenchmarkMetric[];
  selectedModelVersion?: string | null;
  modelIds: string[];
  scope: 'historical' | 'smoke' | 'full';
  hardwareFingerprint?: string | null;
  documentCount: number;
  queryCount: number;
  metricsSchemaVersion: number;
  createdAt: string;
}

export interface SemanticOperation {
  id: string;
  kind: SemanticOperationKind;
  status: SemanticOperationStatus;
  phase: string;
  modelId?: string | null;
  modelIds?: string[];
  modelVersion?: string | null;
  repository?: string | null;
  revision?: string | null;
  progress: {
    current: number;
    total: number;
    unit: string;
  };
  message?: string | null;
  errorCode?: string | null;
  result: Record<string, unknown>;
  actor: string;
  attempts: number;
  leaseOwner?: string | null;
  leaseUntil?: string | null;
  createdAt: string;
  startedAt?: string | null;
  updatedAt: string;
  finishedAt?: string | null;
}

export interface SemanticOperationAccepted {
  operationId: string;
  status: SemanticOperationStatus;
  modelId?: string;
}
