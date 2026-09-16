"use client";

import { FileSearch, Wrench } from "lucide-react";

import { cn } from "@/lib/utils";
import { useI18n } from "@/i18n/provider";
import type { ToolTrace } from "@/lib/api";

/** 工具调用展示：检索类工具显示实际命中的文档（标准号+条款号+页码）与命中数。 */
export function ToolTraceList({ traces, streaming }: { traces: ToolTrace[]; streaming?: boolean }) {
  const { t } = useI18n();
  if (traces.length === 0 && !streaming) return null;

  return (
    <div className="border bg-card">
      <div className="flex items-center gap-1.5 border-b px-3 py-1.5 text-xs text-muted-foreground">
        <Wrench className="size-3.5" />
        {t("chat.tools")}
        {streaming && <span className="text-primary">· {t("chat.retrieving")}</span>}
      </div>
      <div className="divide-y">
        {traces.map((trace, index) => (
          <div key={index} className="slide-in px-3 py-2 text-xs">
            <div className="flex flex-wrap items-center gap-2">
              <span className="font-mono font-medium text-foreground">{trace.tool}</span>
              <span className="bg-accent px-1.5 py-0.5 text-accent-foreground">
                {t("chat.docHit")} {trace.hits}
              </span>
              {trace.note && <span className="text-muted-foreground">{trace.note}</span>}
            </div>
            {trace.args && Object.keys(trace.args).length > 0 && (
              <div className="mt-1 flex flex-wrap gap-x-3 gap-y-0.5 text-muted-foreground">
                {Object.entries(trace.args).map(([key, value]) => (
                  <span key={key} className="font-mono">
                    {key}=
                    <span className="text-foreground">
                      {typeof value === "string" ? value : JSON.stringify(value)}
                    </span>
                  </span>
                ))}
              </div>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}

/** 引用条款卡片：展示标准号、条款号、页码与原文片段。 */
export function CitationList({ citations }: { citations: { standardCode: string; clauseNo: string; title?: string; pageNo?: number | null; quote?: string }[] }) {
  const { t } = useI18n();
  if (citations.length === 0) return null;

  return (
    <div className="mt-2 space-y-1.5">
      <div className="flex items-center gap-1.5 text-xs font-medium text-muted-foreground">
        <FileSearch className="size-3.5" />
        {t("chat.citations")}（{citations.length}）
      </div>
      {citations.map((citation, index) => (
        <div
          key={`${citation.standardCode}-${citation.clauseNo}-${index}`}
          className={cn("border-l-2 border-l-primary bg-muted/40 px-2.5 py-1.5 text-xs")}
        >
          <div className="flex flex-wrap items-center gap-2">
            <span className="font-medium text-primary">
              {citation.standardCode} · {citation.clauseNo}
            </span>
            {citation.pageNo ? (
              <span className="text-muted-foreground">{t("chat.page", { page: citation.pageNo })}</span>
            ) : null}
          </div>
          {citation.title && <div className="mt-0.5 text-foreground">{citation.title}</div>}
          {citation.quote && (
            <div className="mt-1 line-clamp-3 whitespace-pre-wrap text-muted-foreground">{citation.quote}</div>
          )}
        </div>
      ))}
    </div>
  );
}
