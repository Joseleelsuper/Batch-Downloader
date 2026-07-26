import type { OperatingSystem } from '../types/catalog';
import { t } from '../services/i18n';

interface IconProps {
  operatingSystem: OperatingSystem;
  size?: number;
  decorative?: boolean;
}

export function OperatingSystemIcon({
  operatingSystem,
  size = 18,
  decorative = false,
}: Readonly<IconProps>) {
  return (
    <img
      className={`platform-icon platform-icon-${operatingSystem}`}
      src={`/assets/platforms/${operatingSystem}.svg`}
      width={size}
      height={size}
      alt={decorative ? '' : operatingSystemLabel(operatingSystem)}
      aria-hidden={decorative ? true : undefined}
    />
  );
}

export function OperatingSystemList({ operatingSystems }: { operatingSystems: OperatingSystem[] }) {
  if (!operatingSystems.length) {
    return <span className="os-icons os-icons-empty" aria-label="Sin instaladores verificados">—</span>;
  }
  const label = `Disponible para ${operatingSystems.map(operatingSystemLabel).join(', ')}`;
  return (
    <span className="os-icons" aria-label={label}>
      {operatingSystems.map((operatingSystem) => (
        <OperatingSystemIcon key={operatingSystem} operatingSystem={operatingSystem} decorative />
      ))}
      <span className="sr-only">{label}</span>
    </span>
  );
}

export const operatingSystemLabel = (operatingSystem: OperatingSystem) => t(`catalog.platform.${operatingSystem}`);
