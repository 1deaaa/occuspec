"use client";

import { useState } from "react";
import { ChevronDown, BrainCircuit } from "lucide-react";

/** 流式可折叠推理块：推理内容到达即显示，支持折叠。 */
export function ReasoningFold({ texts }: { texts: string[] }) {
  const [open, setOpen] = useState(true);
  return (
    <div className="card">
      <button
        className="transition-sharp flex w-full items-center justify-between bg-brand-light px-3 py-2 text-sm font-semibold"
        onClick={() => setOpen((v) => !v)}
      >
        <span className="flex items-center gap-2">
          <BrainCircuit size={16} />
          推理过程（{texts.length} 段）
        </span>
        <ChevronDown size={16} className={`transition-sharp ${open ? "" : "-rotate-90"}`} />
      </button>
      <div className={`fold-body ${open ? "" : "collapsed"}`}>
        <div className="fold-inner max-h-64 overflow-auto px-3 py-2 text-sm leading-6 text-ink/90">
          {texts.length === 0 ? (
            <span className="text-muted">等待推理输出…</span>
          ) : (
            texts.map((text, idx) => <p key={idx}>{text}</p>)
          )}
        </div>
      </div>
    </div>
  );
}
