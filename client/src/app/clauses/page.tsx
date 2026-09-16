"use client";

import { useState } from "react";
import { BookOpen } from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { ScrollArea } from "@/components/ui/scroll-area";
import { apiFetch } from "@/lib/api";
import { useI18n } from "@/i18n/provider";
import { cn } from "@/lib/utils";

interface Clause {
  id: number;
  standardCode: string;
  clauseNo: string;
  title: string;
  pageNo: number | null;
  appendixType: string;
  hazardCode: string;
  quote: string;
}

export default function ClausesPage() {
  const { t } = useI18n();
  const [keyword, setKeyword] = useState("");
  const [rows, setRows] = useState<Clause[]>([]);
  const [activeId, setActiveId] = useState<number | null>(null);
  const [detail, setDetail] = useState<Record<string, unknown> | null>(null);
  const [loading, setLoading] = useState(false);

  const search = async () => {
    setLoading(true);
    try {
      const data = await apiFetch<{ data: Clause[] }>(
        `/clauses?keyword=${encodeURIComponent(keyword)}&page=1&pageSize=30`,
      );
      setRows(data.data ?? []);
      setDetail(null);
      setActiveId(null);
    } catch {
      setRows([]);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="flex h-full flex-col">
      <div className="shrink-0 border-b bg-card p-3">
        <div className="flex items-center gap-2">
          <BookOpen className="size-4 text-primary" />
          <span className="text-sm font-semibold">{t("nav.clauses")}</span>
          <Input
            className="ml-2 max-w-sm"
            placeholder={t("clauses.searchPlaceholder")}
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && search()}
          />
          <Button size="sm" onClick={search} disabled={loading}>
            {t("action.search")}
          </Button>
        </div>
      </div>

      {/* 左右分栏：列表 + 原文，各自独立滚动 */}
      <div className="grid min-h-0 flex-1 grid-cols-1 md:grid-cols-2">
        <ScrollArea className="min-h-0 border-r">
          <div>
            {rows.length === 0 ? (
              <div className="p-3 text-sm text-muted-foreground">{t("common.empty")}</div>
            ) : (
              rows.map((row) => (
                <button
                  key={row.id}
                  type="button"
                  onClick={async () => {
                    setActiveId(row.id);
                    const data = await apiFetch<Record<string, unknown>>(`/clauses/detail?id=${row.id}`);
                    setDetail(data);
                  }}
                  className={cn(
                    "block w-full border-b px-3 py-2 text-left text-sm transition-colors ease-sharp hover:bg-accent",
                    activeId === row.id ? "bg-accent" : "",
                  )}
                >
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="font-medium text-primary">
                      {row.standardCode} · {row.clauseNo}
                    </span>
                    {row.pageNo ? <Badge variant="outline">p.{row.pageNo}</Badge> : null}
                    {row.hazardCode ? <Badge variant="secondary">{row.hazardCode}</Badge> : null}
                  </div>
                  <div className="mt-0.5 truncate text-foreground">{row.title}</div>
                </button>
              ))
            )}
          </div>
        </ScrollArea>

        <ScrollArea className="min-h-0">
          <div className="p-3">
            {detail ? (
              <>
                <div className="text-sm font-semibold">
                  {String(detail["standardCode"])} · {String(detail["clauseNo"])}
                  {detail["pageNo"] ? `（${t("chat.page", { page: String(detail["pageNo"]) })}）` : ""}
                </div>
                <pre className="mt-2 whitespace-pre-wrap text-sm leading-7">{String(detail["content"] ?? "")}</pre>
              </>
            ) : (
              <div className="text-sm text-muted-foreground">{t("clauses.selectHint")}</div>
            )}
          </div>
        </ScrollArea>
      </div>
    </div>
  );
}
