"use client";

import { Wrench, CheckCircle2 } from "lucide-react";

export interface ToolEntry {
  tool: string;
  args?: Record<string, unknown>;
  hits?: number;
  note?: string;
}

/** 工具调用显示：触发检索时展示实际检索的文档与命中数。 */
export function ToolList({ entries }: { entries: ToolEntry[] }) {
  if (entries.length === 0) {
    return <div className="card px-3 py-2 text-sm text-muted">暂无工具调用</div>;
  }
  return (
    <div className="flex flex-col gap-2">
      {entries.map((entry, idx) => (
        <div key={idx} className="tool-entry card px-3 py-2 text-sm">
          <div className="flex items-center gap-2 font-semibold">
            <Wrench size={15} />
            {entry.tool}
            {entry.hits !== undefined && (
              <span className="bg-brand-light px-1 text-xs">命中 {entry.hits}</span>
            )}
          </div>
          {entry.args && (
            <pre className="mt-1 overflow-auto bg-paper p-2 text-xs text-muted">
              {JSON.stringify(entry.args, null, 1).slice(0, 600)}
            </pre>
          )}
          {entry.note && (
            <div className="mt-1 flex items-start gap-1 text-xs text-ink/80">
              <CheckCircle2 size={14} className="mt-0.5 shrink-0 text-brand" />
              {entry.note}
            </div>
          )}
        </div>
      ))}
    </div>
  );
}
