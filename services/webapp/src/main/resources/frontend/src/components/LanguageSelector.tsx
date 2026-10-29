import { ChevronDown, Languages } from 'lucide-react';
import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react';
import { useLocaleSelector, useTranslation } from '../services/i18n';

const LANGUAGES = [
  { code: 'es', name: 'Español' },
  { code: 'en', name: 'English' },
] as const;

export function LanguageSelector() {
  const t = useTranslation();
  const { locale, selectLocale } = useLocaleSelector();
  const menuId = useId();
  const root = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const options = useRef<Array<HTMLButtonElement | null>>([]);
  const [open, setOpen] = useState(false);
  const [activeIndex, setActiveIndex] = useState(0);

  function focusOption(index: number) {
    setActiveIndex(index);
    window.requestAnimationFrame(() => options.current[index]?.focus());
  }

  function openMenu(index = Math.max(0, LANGUAGES.findIndex(({ code }) => code === locale))) {
    setOpen(true);
    focusOption(index);
  }

  function closeMenu() {
    setOpen(false);
    trigger.current?.focus();
  }

  function handleKeyDown(event: KeyboardEvent<HTMLButtonElement>) {
    if (event.key === 'Escape' && open) {
      event.preventDefault();
      event.stopPropagation();
      closeMenu();
      return;
    }
    const index = event.key === 'ArrowDown' ? (open ? (activeIndex + 1) % LANGUAGES.length : 0)
      : event.key === 'ArrowUp' ? (open ? (activeIndex + LANGUAGES.length - 1) % LANGUAGES.length : LANGUAGES.length - 1)
        : open && event.key === 'Home' ? 0 : open && event.key === 'End' ? LANGUAGES.length - 1 : null;
    if (index !== null) {
      event.preventDefault();
      openMenu(index);
    }
  }

  useEffect(() => {
    if (!open) return;
    const closeOnOutsideClick = (event: PointerEvent) => {
      if (!root.current?.contains(event.target as Node)) setOpen(false);
    };
    document.addEventListener('pointerdown', closeOnOutsideClick);
    return () => document.removeEventListener('pointerdown', closeOnOutsideClick);
  }, [open]);

  return <div className="language-selector" ref={root}
    onBlur={(event) => { if (!event.currentTarget.contains(event.relatedTarget)) setOpen(false); }}>
    <button type="button" className="language-selector-trigger" ref={trigger}
      aria-label={`${t('language.selector')}: ${locale.toUpperCase()}`} aria-haspopup="listbox"
      aria-expanded={open} aria-controls={menuId} onClick={() => open ? closeMenu() : openMenu()} onKeyDown={handleKeyDown}>
      <Languages size={19} aria-hidden="true" /><span>{locale.toUpperCase()}</span>
      <ChevronDown size={15} aria-hidden="true" />
    </button>
    {open ? <div className="language-menu" id={menuId} role="listbox" aria-labelledby={`${menuId}-title`}>
      <p className="language-menu-title" id={`${menuId}-title`}>{t('language.title')}</p>
      {LANGUAGES.map(({ code, name }, index) => <button type="button" role="option" className="language-option"
        key={code} ref={(element) => { options.current[index] = element; }} aria-selected={locale === code}
        tabIndex={activeIndex === index ? 0 : -1} onFocus={() => setActiveIndex(index)} onKeyDown={handleKeyDown}
        onClick={() => { selectLocale(code); closeMenu(); }}>
        <span className="language-option-indicator" aria-hidden="true" />
        <span lang={code}>{name}</span>
        <img className="language-flag" src={`/assets/flags/${code}.svg`} width="24" height="16" alt="" aria-hidden="true" />
      </button>)}
    </div> : null}
  </div>;
}
