export type ResolutionStatus =
  | 'direct'
  | 'fallback'
  | 'requires_manual_review'
  | 'missing'
  | 'broken';

export type ValidationStatus = 'unchecked' | 'valid' | 'invalid' | 'expired';

export interface CatalogApp {
  id: string;
  packageId: string;
  name: string;
  publisher?: string | null;
  description?: string | null;
  iconUrl?: string | null;
  latestVersion?: string | null;
  sourceLabel: string;
  resolutionStatus: ResolutionStatus;
  validationStatus: ValidationStatus;
  downloadable: boolean;
  updatedAt: string;
}

export interface CatalogResponse {
  data: CatalogApp[];
  page: number;
  pageSize: number;
  total: number;
}

export interface CatalogStats {
  total: number;
  filters: Record<FilterKey, number>;
  lastScrape: {
    status: string;
    startedAt: string;
    heartbeatAt: string;
    finishedAt?: string | null;
    appsDiscovered: number;
    appsResolved: number;
    appsFailed: number;
  } | null;
  generatedAt: string;
}

export interface AppDetails extends CatalogApp {
  officialUrl?: string | null;
  originUrl?: string | null;
  installerFilename?: string | null;
  installerType?: string | null;
  contentType?: string | null;
  sizeBytes?: number | null;
  finalDomain?: string | null;
  score?: number | null;
  checkedAt?: string | null;
  expiresAt?: string | null;
  notes: string;
}

export type FilterKey = 'all' | 'available' | 'review' | 'missing';

export type SortKey = 'name' | 'updated';
