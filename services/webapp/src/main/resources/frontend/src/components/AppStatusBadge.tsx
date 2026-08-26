import type { ResolutionStatus } from '../types/catalog';
import { useTranslation } from '../services/i18n';

interface Props {
  status: ResolutionStatus;
}

export function AppStatusBadge({ status }: Props) {
  const t = useTranslation();
  return (
    <span className={`status-badge status-${status}`}>
      {t(`status.${status}` as const)}
    </span>
  );
}
