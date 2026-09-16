"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import {
  Activity,
  ClipboardList,
  FileText,
  BookOpen,
  SlidersHorizontal,
  History,
  Layers,
  LogOut,
  FlaskConical,
} from "lucide-react";
import { useI18n } from "@/i18n/provider";
import { clearToken, getToken } from "@/lib/api";
import { useEffect, useState } from "react";

const NAV = [
  { href: "/", key: "nav.workspace", icon: Activity },
  { href: "/exams", key: "nav.exams", icon: ClipboardList },
  { href: "/assess", key: "nav.assess", icon: FlaskConical },
  { href: "/reports", key: "nav.reports", icon: FileText },
  { href: "/clauses", key: "nav.clauses", icon: BookOpen },
  { href: "/rules", key: "nav.rules", icon: SlidersHorizontal },
  { href: "/audits", key: "nav.audits", icon: History },
  { href: "/batch", key: "nav.batch", icon: Layers },
] as const;

export function AppShell({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const { t, locale, setLocale } = useI18n();
  const [authed, setAuthed] = useState(false);

  useEffect(() => {
    setAuthed(!!getToken());
  }, [pathname]);

  return (
    <div className="min-h-screen bg-paper text-ink">
      <header className="border-b border-line bg-white">
        <div className="mx-auto flex max-w-6xl items-center justify-between px-4 py-3">
          <div>
            <div className="text-lg font-bold tracking-tight">{t("app.title")}</div>
            <div className="text-xs text-muted">{t("app.disclaimer")}</div>
          </div>
          <div className="flex items-center gap-2">
            <button
              className="transition-sharp border border-line bg-white px-2 py-1 text-xs"
              onClick={() => setLocale(locale === "zh-CN" ? "en-US" : "zh-CN")}
            >
              {locale === "zh-CN" ? "EN" : "中文"}
            </button>
            {authed ? (
              <button
                className="transition-sharp flex items-center gap-1 border border-line bg-white px-2 py-1 text-xs"
                onClick={() => {
                  clearToken();
                  setAuthed(false);
                  router.push("/login");
                }}
              >
                <LogOut size={14} />
                {t("nav.logout")}
              </button>
            ) : (
              <Link href="/login" className="btn-primary px-3 py-1 text-xs">
                {t("nav.login")}
              </Link>
            )}
          </div>
        </div>
      </header>
      <div className="mx-auto flex max-w-6xl gap-4 px-4 py-4">
        <aside className="card w-44 shrink-0 p-2">
          <nav className="flex flex-col gap-1">
            {NAV.map((item) => {
              const Icon = item.icon;
              const active = pathname === item.href;
              return (
                <Link
                  key={item.href}
                  href={item.href}
                  className={`nav-item transition-sharp flex items-center gap-2 px-2 py-2 text-sm ${active ? "active" : ""}`}
                >
                  <Icon size={16} />
                  {t(item.key)}
                </Link>
              );
            })}
          </nav>
        </aside>
        <main className="min-w-0 flex-1">{children}</main>
      </div>
    </div>
  );
}
