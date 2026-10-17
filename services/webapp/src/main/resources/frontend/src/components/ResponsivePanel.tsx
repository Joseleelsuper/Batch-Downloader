import { Menu, X } from 'lucide-react';
import { useEffect, useRef, type ReactNode } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { useCompactLayout } from '../hooks/useCompactLayout';
import { useTranslation } from '../services/i18n';

/** Conserva el panel de escritorio y protege el foco cuando se abre en móvil. */
export function ResponsivePanel({
  id, title, open, onClose, children, desktopClassName, desktopHidden = false, footer,
}: Readonly<{
  id: string;
  title: string;
  open: boolean;
  onClose: () => void;
  children: ReactNode;
  desktopClassName: string;
  desktopHidden?: boolean;
  footer?: ReactNode;
}>) {
  const t = useTranslation();
  const compact = useCompactLayout();
  const dialog = useRef<HTMLDialogElement>(null);
  const backdropPressed = useRef(false);
  const { pathname } = useLocation();
  const previousPath = useRef(pathname);

  useEffect(() => {
    if (previousPath.current !== pathname || !compact) onClose();
    previousPath.current = pathname;
  }, [compact, onClose, pathname]);

  useEffect(() => {
    if (!compact || !open) return;
    const element = dialog.current;
    const trigger = document.activeElement;
    const previousOverflow = document.body.style.overflow;
    element?.showModal();
    document.body.style.overflow = 'hidden';
    return () => {
      element?.close();
      document.body.style.overflow = previousOverflow;
      if (trigger instanceof HTMLElement && trigger.isConnected) trigger.focus({ preventScroll: true });
    };
  }, [compact, open]);

  if (!compact) {
    return <div id={id} className={desktopClassName} hidden={desktopHidden}>{children}</div>;
  }

  return (
    <dialog ref={dialog} id={id} className="mobile-drawer" aria-labelledby={`${id}-title`}
      onCancel={(event) => { event.preventDefault(); onClose(); }}
      onPointerDown={(event) => { backdropPressed.current = event.target === event.currentTarget; }}
      onPointerUp={(event) => {
        if (backdropPressed.current && event.target === event.currentTarget) onClose();
        backdropPressed.current = false;
      }}>
      <div className="mobile-drawer-layout">
        <header className="mobile-drawer-header">
          <h2 id={`${id}-title`}>{title}</h2>
          <button className="mobile-icon-button" type="button" onClick={onClose} aria-label={t('common.close')}>
            <X size={22} aria-hidden="true" />
          </button>
        </header>
        <div className="mobile-drawer-content">{children}</div>
        {footer ? <footer className="mobile-drawer-footer">{footer}</footer> : null}
      </div>
    </dialog>
  );
}

export function MobileMenuButton({ controls, open, onClick }: Readonly<{
  controls: string;
  open: boolean;
  onClick: () => void;
}>) {
  const t = useTranslation();
  return <button className="mobile-menu-button mobile-icon-button" type="button"
    aria-label={t('nav.openMenu')} aria-controls={controls} aria-expanded={open} onClick={onClick}>
    <Menu size={23} aria-hidden="true" />
  </button>;
}

export function MobileAppHeader(props: Readonly<{ controls: string; open: boolean; onClick: () => void }>) {
  const t = useTranslation();
  return <header className="mobile-app-header">
    <Link className="brand" to="/">
      <img className="brand-icon" src="/assets/icon.ico" alt="" />
      <span className="brand-title">{t('app.title')}</span>
    </Link>
    <MobileMenuButton {...props} />
  </header>;
}
