"use client";

import { createContext, useCallback, useContext, useMemo, useState } from "react";
import { enUS, zhCN, type I18nKey } from "./messages";

type Locale = "zh-CN" | "en-US";

const dicts: Record<Locale, Record<string, string>> = {
  "zh-CN": zhCN,
  "en-US": enUS,
};

type TranslateFn = (key: I18nKey, params?: Record<string, string | number>) => string;

const I18nContext = createContext<{
  locale: Locale;
  setLocale: (locale: Locale) => void;
  t: TranslateFn;
}>({
  locale: "zh-CN",
  setLocale: () => {},
  t: (key) => zhCN[key] ?? key,
});

export function I18nProvider({ children }: { children: React.ReactNode }) {
  const [locale, setLocale] = useState<Locale>("zh-CN");

  /** 取词条并替换 {name} 占位符。 */
  const t = useCallback<TranslateFn>(
    (key, params) => {
      const template = dicts[locale][key] ?? zhCN[key] ?? key;
      if (!params) return template;
      return Object.entries(params).reduce(
        (text, [name, value]) => text.replaceAll(`{${name}}`, String(value)),
        template,
      );
    },
    [locale],
  );

  const value = useMemo(() => ({ locale, setLocale, t }), [locale, t]);
  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}

export function useI18n() {
  return useContext(I18nContext);
}
