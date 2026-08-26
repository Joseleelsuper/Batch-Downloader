import type { OperatingSystem } from '../types/catalog';
import { t, useTranslation, type Translator } from '../services/i18n';

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
  const translate = useTranslation();
  return (
    <img
      className={`platform-icon platform-icon-${operatingSystem}`}
      src={`/assets/platforms/${operatingSystem}.svg?v=20260726-2`}
      width={size}
      height={size}
      alt={decorative ? '' : operatingSystemLabel(operatingSystem, translate)}
      aria-hidden={decorative ? true : undefined}
    />
  );
}

export function OperatingSystemList({ operatingSystems }: { operatingSystems: OperatingSystem[] }) {
  const translate = useTranslation();
  if (!operatingSystems.length) {
    return <span className="os-icons os-icons-empty" aria-label="Sin instaladores verificados">—</span>;
  }
  const label = `Disponible para ${operatingSystems
    .map((operatingSystem) => operatingSystemLabel(operatingSystem, translate))
    .join(', ')}`;
  return (
    <span className="os-icons" aria-label={label}>
      {operatingSystems.map((operatingSystem) => (
        <OperatingSystemIcon key={operatingSystem} operatingSystem={operatingSystem} decorative />
      ))}
      <span className="sr-only">{label}</span>
    </span>
  );
}

export const operatingSystemLabel = (
  operatingSystem: OperatingSystem,
  translate: Translator = t,
) => translate(`catalog.platform.${operatingSystem}`);
