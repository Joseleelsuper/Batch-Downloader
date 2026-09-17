export interface SemanticIndexState {
  indexVersion?: string | null;
  expected: number;
  indexed: number;
  complete: boolean;
  builtAt?: string | null;
}

export interface SemanticModelStatus {
  version?: string | null;
  dimensions?: number | null;
  artifactReady: boolean;
}

export interface SemanticWorkerStatus {
  present?: boolean;
  healthy?: boolean;
  reason?: string;
  ageSeconds?: number | null;
  lastErrorCode?: string | null;
  consecutiveFailures?: number;
}

export interface SemanticOverview {
  service: string;
  status: 'ok' | 'degraded';
  database: boolean;
  searchReady: boolean;
  model: SemanticModelStatus | null;
  index: SemanticIndexState;
  indexer: SemanticWorkerStatus;
}
