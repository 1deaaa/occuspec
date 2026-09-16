import type { Metadata } from "next";
import "./globals.css";
import { AppShell } from "@/components/app-shell";
import { I18nProvider } from "@/i18n/provider";

export const metadata: Metadata = {
  title: "职业健康辅助判定系统",
  description: "辅助判定工具，结论为建议性质，须经主检医师复核。",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="zh-CN" className="h-full">
      <body className="min-h-full">
        <I18nProvider>
          <AppShell>{children}</AppShell>
        </I18nProvider>
      </body>
    </html>
  );
}
