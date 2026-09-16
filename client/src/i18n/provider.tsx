"use client";

import { createContext, useCallback, useContext, useMemo, useState } from "react";
import { enUS, zhCN, type I18nKey } from "./messages";

type Locale = "zh-CN" | "en-US";

const dicts: Record<Locale, Record<string, string>> = {
  "zh-CN": zhCN,
  "en-US": enUS,
};

const I18nContext = createContext<{
  locale: Locale;
  setLocale: (locale: Locale) => void;
  t: (key: I18nKey) => string;
}>({
  locale: "zh-CN",
  setLocale: () => {},
  t: (key) => zhCN[key] ?? key,
});

export function I18nProvider({ children }: { children: React.ReactNode }) {
  const [locale, setLocale] = useState<Locale>("zh-CN");
  const t = useCallback((key: I18nKey) => dicts[locale][key] ?? zhCN[key] ?? key, [locale]);
  const value = useMemo(() => ({ locale, setLocale, t }), [locale, t]);
  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}

export function useI18n() {
  return useContext(I18nContext);
}
