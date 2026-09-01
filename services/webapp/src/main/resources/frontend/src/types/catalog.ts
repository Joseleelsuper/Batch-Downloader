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
  alphabet?: CatalogAlphabetEntry[];
  requestedMode?: SearchMode;
  appliedMode?: SearchMode;
  modelVersion?: string | null;
  indexVersion?: string | null;
  degradedReason?: string | null;
}

export interface CatalogAlphabetEntry {
  letter: string;
  page: number;
  count: number;
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
  | 'MANUAL_ONLY'
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
  appName: string;
  officialPageUrl?: string | null;
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
  artifactSizeBytes?: number | null;
  artifactSha256?: string | null;
  waitReason?: string | null;
  retryAt?: string | null;
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
export type AdminAppFilter = FilterKey | 'unresolved';

export type SortKey = 'name' | 'updated' | 'downloads';

export type ManualSuggestionSource =
  | 'current'
  | 'json_ld'
  | 'open_graph'
  | 'twitter'
  | 'canonical'
  | 'filename'
  | 'generated_ai'
  | 'manual'
  | 'source_page'
  | 'unavailable';

export interface ManualFieldSuggestion {
  value?: string | null;
  source: ManualSuggestionSource;
}

export interface ManualInstallerSuggestions {
  name: ManualFieldSuggestion;
  publisher: ManualFieldSuggestion;
  officialUrl: ManualFieldSuggestion;
  latestVersion: ManualFieldSuggestion;
  description: ManualFieldSuggestion;
  longDescription: ManualFieldSuggestion;
  iconUrl: ManualFieldSuggestion;
}

export interface ManualInstallerTechnicalData {
  finalDomain?: string | null;
  filename?: string | null;
  extension?: string | null;
  contentType?: string | null;
  sizeBytes?: number | null;
  version?: string | null;
  operatingSystem?: OperatingSystem | null;
  architecture: string;
  platformRequired: boolean;
}

export interface ManualInstallerInspection {
  id: string;
  appId: string;
  status: 'queued' | 'running' | 'ready' | 'failed' | 'applied' | 'expired';
  phase: string;
  expectedAppVersion: number;
  warnings: string[];
  suggestions?: ManualInstallerSuggestions | null;
  installer?: ManualInstallerTechnicalData | null;
  installers: ManualInstallerTechnicalData[];
  ai?: {
    status: 'ready' | 'unavailable' | 'failed';
    provider?: string | null;
    model?: string | null;
  } | null;
  errorCode?: string | null;
  sourceRef?: string | null;
  createdAt: string;
  updatedAt: string;
  expiresAt: string;
}

export interface ManualInstallerInspectionRequest {
  installerUrls: Record<OperatingSystem, string | null>;
  sourcePageUrl: string;
}

export interface ManualInstallerApplyRequest {
  expectedAppVersion: number;
  name: string;
  publisher: string | null;
  officialUrl: string | null;
  latestVersion: string | null;
  description: string | null;
  longDescription: string | null;
  iconUrl: string | null;
  operatingSystem?: OperatingSystem | null;
}

export interface ManualInstallerApplyResponse {
  application: AppDetails;
  sourceRef: string;
  sourceRefs: string[];
  warnings: string[];
}

export interface WebsiteAppDiscoveryInstaller {
  id: string;
  finalDomain?: string | null;
  filename?: string | null;
  extension?: string | null;
  contentType?: string | null;
  sizeBytes?: number | null;
  version?: string | null;
  operatingSystem: OperatingSystem;
  architecture: string;
}

export interface WebsiteAppDiscovery {
  id: string;
  status: 'queued' | 'running' | 'ready' | 'failed' | 'applied' | 'expired';
  phase: string;
  warnings: string[];
  providedInstallerPlatforms: OperatingSystem[];
  suggestions?: ManualInstallerSuggestions | null;
  installers: WebsiteAppDiscoveryInstaller[];
  ai?: ManualInstallerInspection['ai'];
  errorCode?: string | null;
  appliedAppId?: string | null;
  createdAt: string;
  updatedAt: string;
  expiresAt: string;
}

export interface WebsiteAppDiscoveryRequest {
  officialUrl: string;
  installerUrls: Record<OperatingSystem, string | null>;
}

export interface WebsiteAppDiscoveryApplyRequest {
  name: string;
  publisher: string | null;
  officialUrl: string;
  latestVersion: string | null;
  description: string | null;
  longDescription: string | null;
  iconUrl: string | null;
}

export interface WebsiteAppDiscoveryApplyResponse {
  application: AppDetails;
  installerCount: number;
  warnings: string[];
}

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
  platformAvailability: BundlePlatformAvailability[];
  previewApps: CatalogApp[];
  updatedAt: string;
}

export interface BundlePlatformAvailability {
  operatingSystem: OperatingSystem;
  downloadableAppCount: number;
  previewApps: CatalogApp[];
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
  id: string;
  username: string;
  email: string;
  emailVerified: boolean;
  role: 'USER' | 'ADMIN';
  notifyOnJobCompletion: boolean;
  createdAt: string;
}

export interface ScraperRunSummary {
  id: string;
  status: string;
  scope: ScrapeScope;
  requestId?: string | null;
  targetCount: number;
  startedAt: string;
  heartbeatAt: string;
  finishedAt?: string | null;
  appsDiscovered: number;
  appsResolved: number;
  appsFailed: number;
  appsSkipped: number;
  appsConfirmedMissing: number;
  appsNeedsReview: number;
  appsTransientFailed: number;
  appsSkippedUnchanged: number;
  currentPackageId?: string | null;
  currentAppName?: string | null;
  currentPhase?: string | null;
  stopRequested: boolean;
  pausedAt?: string | null;
  errorSummary?: string | null;
}

export type ScrapeScope = 'incremental' | 'unresolved' | 'selected' | 'full';

export interface ScraperRunRequestResponse {
  requestId: string;
  scope: ScrapeScope;
  status: string;
}

export interface InstallerAbsenceVerificationRequest {
  reasonCode: 'no_supported_binary' | 'store_only' | 'command_only' | 'wrapper_only' | 'vendor_discontinued';
  manifestUrl: string;
  officialPageUrl?: string | null;
  winstallConfirmedAbsent: true;
  manifestConfirmedAbsent: true;
  officialConfirmedAbsent: boolean;
  ambiguousAccess: false;
  notes?: string | null;
}

export interface InstallerAbsenceVerification {
  id: string;
  appId: string;
  status: string;
  reasonCode: string;
  notes?: string | null;
  checkedUrls: string;
  verifiedBy: string;
  verifiedAt: string;
  appVersion: number;
  winstallLatestVersion?: string | null;
  winstallSummaryFingerprint?: string | null;
  winstallDetailFingerprint?: string | null;
  officialUrlFingerprint?: string | null;
  invalidatedAt?: string | null;
  invalidationReason?: string | null;
}

export interface InstallerAbsenceVerificationSummary {
  active: number;
  missing: number;
  missingWithoutActiveEvidence: number;
  review: number;
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
