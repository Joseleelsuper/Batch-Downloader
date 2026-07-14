import type { OperatingSystem } from '../types/catalog';
import { t } from '../services/i18n';

interface IconProps {
  operatingSystem: OperatingSystem;
  size?: number;
  decorative?: boolean;
}

/** Local, lightweight platform glyphs; no brand-icon dependency is required. */
export function OperatingSystemIcon({ operatingSystem, size = 18, decorative = false }: Readonly<IconProps>) {
  const common = {
    width: size,
    height: size,
    viewBox: '0 0 24 24',
    fill: 'none',
    stroke: 'currentColor',
    strokeWidth: 1.8,
    strokeLinecap: 'round' as const,
    strokeLinejoin: 'round' as const,
    'aria-hidden': decorative ? true : undefined,
    role: decorative ? undefined : 'img',
  };
  const title = decorative ? null : <title>{operatingSystemLabel(operatingSystem)}</title>;
  if (operatingSystem === 'windows') {
    return (
      <svg {...common}>
        {title}
        <path d="M3 5.5 10.5 4v7H3zM12.5 3.7 21 2.5v8.5h-8.5zM3 13h7.5v7L3 18.7zM12.5 13H21v8.5l-8.5-1.2z" />
      </svg>
    );
  }
  if (operatingSystem === 'linux') {
    return (
      <svg {...common}>
        {title}
        <circle cx="12" cy="7" r="3.2" />
        <path d="M7.5 20c.2-5.1 1.6-8 4.5-8s4.3 2.9 4.5 8M9 8.7 7 11m8-2.3 2 2.3M9.3 16.4h5.4M8 20h8" />
      </svg>
    );
  }
  return (
    <svg {...common}>
      {title}
      <path d="M15.4 7.1c.8-1 1.3-2.2 1.2-3.4-1.2.1-2.5.8-3.3 1.8-.7.8-1.3 2-1.1 3.2 1.3.1 2.5-.6 3.2-1.6Z" />
      <path d="M18.1 12.8c0-2.4 1.9-3.5 2-3.6-1.1-1.6-2.8-1.8-3.4-1.8-1.4-.2-2.8.9-3.5.9-.8 0-1.9-.9-3.1-.9-1.6 0-3 .9-3.8 2.3-1.7 2.9-.4 7.1 1.2 9.4.8 1.1 1.7 2.4 3 2.4 1.2 0 1.7-.8 3.2-.8s1.9.8 3.2.8c1.3 0 2.2-1.2 3-2.3.9-1.3 1.3-2.6 1.3-2.7-.1 0-2.1-.8-2.1-3.7Z" />
    </svg>
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
