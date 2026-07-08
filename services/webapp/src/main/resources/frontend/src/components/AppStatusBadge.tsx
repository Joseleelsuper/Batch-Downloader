import type { ResolutionStatus } from '../types/catalog';
import { t } from '../services/i18n';

interface Props {
  status: ResolutionStatus;
}

export function AppStatusBadge({ status }: Props) {
  return (
    <span className={`status-badge status-${status}`}>
      {t(`status.${status}` as const)}
    </span>
  );
}
