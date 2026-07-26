export type ResolutionStatus =
  | 'direct'
  | 'fallback'
  | 'requires_manual_review'
  | 'missing'
  | 'broken';

export type ValidationStatus = 'unchecked' | 'valid' | 'invalid' | 'expired';

export type OperatingSystem = 'windows' | 'linux' | 'macos';
export type SearchMode = 'lexical' | 'semantic';

export interface CatalogApp {
  id: string;
  slug: string;
  packageId: string;
  name: string;
  publisher?: string | null;
  description?: string | null;
  longDescription?: string | null;
  tags: string[];
  operatingSystems: OperatingSystem[];
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
  requestedMode?: SearchMode;
  appliedMode?: SearchMode;
  modelVersion?: string | null;
  indexVersion?: string | null;
  degradedReason?: string | null;
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
  requestedMode?: SearchMode;
  appliedMode?: SearchMode;
  modelVersion?: string | null;
  indexVersion?: string | null;
  degradedReason?: string | null;
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
    appsSkipped?: number;
  } | null;
  generatedAt: string;
}

export interface DownloadOption {
  id: string;
  filename?: string | null;
  extension?: string | null;
  operatingSystem: string;
  architecture: string;
  version?: string | null;
  isLatest: boolean;
  versionStatus?: string | null;
  sourceLabel: string;
  score: number;
  finalDomain?: string | null;
  isPrimary: boolean;
}

export type DownloadJobStatus =
  | 'QUEUED'
  | 'RESOLVING'
  | 'DOWNLOADING'
  | 'PACKAGING'
  | 'READY'
  | 'PARTIAL'
  | 'FAILED'
  | 'CANCELLED'
  | 'EXPIRED';

export type DownloadItemStatus =
  | 'QUEUED'
  | 'RESOLVING'
  | 'DOWNLOADING'
  | 'COMPLETED'
  | 'FAILED'
  | 'CANCELLED';

export interface DownloadJobItem {
  id: string;
  appId: string;
  status: DownloadItemStatus;
  bytesDownloaded: number;
  sha256?: string | null;
  errorCode?: string | null;
}

export interface DownloadJob {
  id: string;
  status: DownloadJobStatus;
  failureCode: string | null;
  progress: number;
  requestedCount: number;
  acceptedCount: number;
  omittedCount: number;
  items: DownloadJobItem[];
  createdAt: string;
  expiresAt: string;
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

export type SortKey = 'name' | 'updated' | 'downloads';

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
  /** Platforms with a selectable installer for at least one active app. */
  operatingSystems: OperatingSystem[];
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

export interface ScraperQueueItem {
  id: string;
  packageId: string;
  appName?: string | null;
  status: string;
  attempts: number;
  updatedAt: string;
}

export interface ScraperQueueState {
  queue: string;
  queued: number;
  inProgress: number;
  completed: number;
  discarded: number;
  failed: number;
  items: ScraperQueueItem[];
}

export interface ScraperMetricItem {
  available: number;
  review: number;
  unavailable: number;
  queuedSearcherFilter: number;
  queuedFilterScraper: number;
  queuedScraperSoFilter?: number;
  queuedSoFilterDescriptor?: number;
  queuedScraperDescriptor?: number;
  capturedAt: string;
}

export interface ContentEnqueueResult {
  matched: number;
  enqueued: number;
  alreadyActive: number;
}

export interface ScraperSnapshotItem {
  stage: string;
  packageId?: string | null;
  appName?: string | null;
  url?: string | null;
  html?: string | null;
  capturedAt: string;
}

export interface ScraperEvent {
  type: 'scraper.changed';
  version: string;
  queues: ScraperQueueState[];
  metrics: ScraperMetricItem[];
  snapshots: ScraperSnapshotItem[];
  generatedAt: string;
}

export interface ScraperQueueMaintenanceResult {
  action: string;
  affected: number;
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
