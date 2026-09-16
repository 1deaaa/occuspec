"use client";

import { useState } from "react";
import { BrainCircuit, ChevronDown } from "lucide-react";

import { cn } from "@/lib/utils";
import { useI18n } from "@/i18n/provider";

/** 流式可折叠推理块：推理增量到达即渲染，点击折叠，行高过渡平滑。 */
export function ReasoningFold({ text, streaming }: { text: string; streaming?: boolean }) {
  const { t } = useI18n();
  const [open, setOpen] = useState(false);

  if (!text) return null;

  return (
    <div className="border bg-muted/40">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        className="flex w-full items-center justify-between px-3 py-1.5 text-xs text-muted-foreground transition-colors ease-sharp hover:text-foreground"
      >
        <span className="flex items-center gap-1.5">
          <BrainCircuit className="size-3.5" />
          {t("chat.reasoning")}
          {streaming && <span className="text-primary">· {t("chat.thinking")}</span>}
        </span>
        <ChevronDown className={cn("size-3.5 transition-transform duration-200 ease-sharp", open ? "" : "-rotate-90")} />
      </button>
      <div className={cn("fold-grid", open ? "" : "collapsed")}>
        <div>
          <div className="max-h-56 overflow-auto border-t px-3 py-2 text-xs leading-6 text-muted-foreground whitespace-pre-wrap">
            {text}
          </div>
        </div>
      </div>
    </div>
  );
}
