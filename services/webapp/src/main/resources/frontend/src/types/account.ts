import type { CatalogApp, AuthUser } from './catalog';

export interface OwnBundleSummary {
  id: string;
  slug: string;
  name: string;
  description?: string | null;
  visibility: 'private' | 'public';
  appCount: number;
  tags: string[];
  updatedAt: string;
  version: number;
}

export interface OwnBundleDetails extends OwnBundleSummary {
  apps: CatalogApp[];
}

export interface OwnBundlePage {
  data: OwnBundleSummary[];
  page: number;
  pageSize: number;
  total: number;
}

export interface DownloadHistoryItem {
  appId: string;
  appName: string;
  slug?: string | null;
  iconUrl?: string | null;
  jobId: string;
  downloadedAt: string;
}

export interface DownloadHistoryPage {
  data: DownloadHistoryItem[];
  page: number;
  pageSize: number;
  total: number;
}

export interface AccountDashboard {
  account: AuthUser;
  counts: {
    bundles: number;
    publicBundles: number;
    privateBundles: number;
    downloads: number;
  };
  recentDownloads: DownloadHistoryItem[];
  recentBundles: OwnBundleSummary[];
}

export interface OwnBundleInput {
  name: string;
  description: string;
  slug: string;
  tags: string[];
  appIds: string[];
}
