export type ResolutionStatus =
  | 'direct'
  | 'fallback'
  | 'requires_manual_review'
  | 'missing'
  | 'broken';

export type ValidationStatus = 'unchecked' | 'valid' | 'invalid' | 'expired';

export interface CatalogApp {
  id: string;
  slug: string;
  packageId: string;
  name: string;
  publisher?: string | null;
  description?: string | null;
  longDescription?: string | null;
  tags: string[];
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

export interface FacetItem {
  label: string;
  value: string;
  normalizedValue: string;
  letter: string;
  count: number;
}

export interface CatalogFacets {
  tags: FacetItem[];
  publishers: FacetItem[];
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

export interface DownloadOption {
  id: string;
  filename?: string | null;
  extension?: string | null;
  sourceLabel: string;
  score: number;
  finalDomain?: string | null;
  isPrimary: boolean;
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
  downloadOptions?: DownloadOption[];
  notes: string;
}

export type FilterKey = 'all' | 'available' | 'review' | 'missing';

export type SortKey = 'name' | 'updated';

export interface BundleSummary {
  id: string;
  slug: string;
  name: string;
  description?: string | null;
  type: 'official' | 'community' | 'user';
  visibility: string;
  starCount: number;
  appCount: number;
  tags: string[];
  previewApps: CatalogApp[];
  updatedAt: string;
}

export interface BundleDetails extends BundleSummary {
  apps: CatalogApp[];
}

export interface BundleResponse {
  data: BundleSummary[];
  page: number;
  pageSize: number;
  total: number;
}

export interface AuthUser {
  username: string;
  role: string;
}

export interface ScraperRunSummary {
  id: string;
  status: string;
  startedAt: string;
  heartbeatAt: string;
  finishedAt?: string | null;
  appsDiscovered: number;
  appsResolved: number;
  appsFailed: number;
  appsSkipped: number;
  currentPackageId?: string | null;
  currentAppName?: string | null;
  currentPhase?: string | null;
  stopRequested: boolean;
  pausedAt?: string | null;
  errorSummary?: string | null;
}

export interface ResolverLogItem {
  id: string;
  phase: string;
  status: string;
  message?: string | null;
  safeMetadata?: string | null;
  createdAt: string;
}

export interface CatalogChangeEvent {
  type: 'catalog.changed';
  version: string;
  generatedAt: string;
}

export interface SoftwareRequestItem {
  id: string;
  requestedName: string;
  officialUrl: string;
  description?: string | null;
  generatedDescription?: string | null;
  status: string;
  requesterEmail?: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface AuditItem {
  actor: string;
  action: string;
  targetType: string;
  targetId?: string | null;
  safeMetadata?: string | null;
  createdAt: string;
}
