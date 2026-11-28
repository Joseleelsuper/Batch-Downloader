import { ChevronDown, Languages } from 'lucide-react';
import { useListbox } from '../hooks/useListbox';
import { useLocaleSelector, useTranslation } from '../services/i18n';

const LANGUAGES = [
  { code: 'es', name: 'Español' },
  { code: 'en', name: 'English' },
] as const;

export function LanguageSelector() {
  const t = useTranslation();
  const { locale, selectLocale } = useLocaleSelector();
  const { close, visible, rootProps, triggerProps, listProps, optionProps } = useListbox(
    LANGUAGES.length, LANGUAGES.findIndex(({ code }) => code === locale), true,
  );
  const titleId = `${listProps.id}-title`;

  return <div className="language-selector" {...rootProps}>
    <button type="button" className="language-selector-trigger" {...triggerProps}
      aria-label={`${t('language.selector')}: ${locale.toUpperCase()}`} aria-haspopup="listbox">
      <Languages size={19} aria-hidden="true" /><span>{locale.toUpperCase()}</span>
      <ChevronDown className="sort-menu-chevron" size={15} aria-hidden="true" />
    </button>
    {visible ? <div className="language-menu" {...listProps} role="listbox" aria-labelledby={titleId}>
      <p className="language-menu-title" id={titleId}>{t('language.title')}</p>
      {LANGUAGES.map(({ code, name }, index) => <button type="button" role="option" className="language-option"
        key={code} {...optionProps(index)} aria-selected={locale === code}
        onClick={() => { selectLocale(code); close(); }}>
        <span className="language-option-indicator" aria-hidden="true" />
        <span lang={code}>{name}</span>
        <img className="language-flag" src={`/assets/flags/${code}.svg`} width="24" height="16" alt="" aria-hidden="true" />
      </button>)}
    </div> : null}
  </div>;
}
