"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import {
  Activity,
  BookOpen,
  ClipboardList,
  FileText,
  FileUp,
  FlaskConical,
  History,
  Layers,
  LogOut,
  MessagesSquare,
  SlidersHorizontal,
} from "lucide-react";

import { Sidebar } from "@/components/ui/sidebar";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { useI18n } from "@/i18n/provider";
import { useAuthToken } from "@/lib/auth";
import { cn } from "@/lib/utils";

/** 对话为默认入口；专业模式收纳批量/复核/审计/规则等机构功能。 */
const CHAT_NAV = [{ href: "/", key: "nav.chat", icon: MessagesSquare }] as const;

const PROFESSIONAL_NAV = [
  { href: "/exams", key: "nav.exams", icon: ClipboardList },
  { href: "/reports/upload", key: "nav.reportsUpload", icon: FileUp },
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
  const { isAuthed, clear } = useAuthToken();

  const renderGroup = (title: string, items: readonly { href: string; key: string; icon: typeof Activity }[]) => (
    <div className="px-2 py-2">
      <div className="px-2 pb-1 text-[10px] font-medium uppercase tracking-wider text-muted-foreground">
        {title}
      </div>
      <nav className="flex flex-col">
        {items.map((item) => {
          const Icon = item.icon;
          const active = pathname === item.href;
          return (
            <Link
              key={item.href}
              href={item.href}
              className={cn(
                "flex items-center gap-2 border-l-2 px-2 py-2 text-sm transition-colors ease-sharp",
                active
                  ? "border-l-primary bg-accent font-medium text-accent-foreground"
                  : "border-l-transparent text-foreground hover:bg-accent/60",
              )}
            >
              <Icon className="size-4 shrink-0" />
              <span className="truncate">{t(item.key as never)}</span>
            </Link>
          );
        })}
      </nav>
    </div>
  );

  return (
    <div className="flex h-screen w-screen overflow-hidden">
      <Sidebar
        footer={
          <div className="px-2 pb-1 pt-1">
            <Badge variant="outline" className="w-full justify-center text-[10px]">
              {t("app.assistOnly")}
            </Badge>
          </div>
        }
      >
        <div className="flex h-14 items-center border-b px-3">
          <Activity className="mr-2 size-5 shrink-0 text-primary" />
          <span className="truncate text-sm font-semibold">{t("app.shortTitle")}</span>
        </div>
        {renderGroup(t("nav.groupChat"), CHAT_NAV)}
        {renderGroup(t("nav.groupProfessional"), PROFESSIONAL_NAV)}
      </Sidebar>

      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex h-14 shrink-0 items-center justify-between border-b bg-card px-4">
          <div className="min-w-0">
            <div className="truncate text-sm font-semibold">{t("app.title")}</div>
            <div className="truncate text-xs text-muted-foreground">{t("app.disclaimer")}</div>
          </div>
          <div className="flex shrink-0 items-center gap-2">
            <Button
              variant="outline"
              size="sm"
              onClick={() => setLocale(locale === "zh-CN" ? "en-US" : "zh-CN")}
            >
              {locale === "zh-CN" ? "EN" : "中文"}
            </Button>
            {isAuthed ? (
              <Button
                variant="outline"
                size="sm"
                onClick={() => {
                  clear();
                  router.push("/login");
                }}
              >
                <LogOut />
                {t("nav.logout")}
              </Button>
            ) : (
              <Button size="sm" asChild>
                <Link href="/login">{t("nav.login")}</Link>
              </Button>
            )}
          </div>
        </header>

        <main className="min-h-0 flex-1 overflow-hidden">{children}</main>
      </div>
    </div>
  );
}
