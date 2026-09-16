"use client";

import { useState } from "react";
import { streamAssess, type StreamEvent } from "@/lib/api";
import { useI18n } from "@/i18n/provider";
import { ReasoningFold } from "@/components/reasoning-fold";
import { ToolList, type ToolEntry } from "@/components/tool-list";
import { UsageBar } from "@/components/usage-bar";

export default function AssessPage() {
  const { t } = useI18n();
  const [examId, setExamId] = useState("");
  const [running, setRunning] = useState(false);
  const [reasoning, setReasoning] = useState<string[]>([]);
  const [tools, setTools] = useState<ToolEntry[]>([]);
  const [content, setContent] = useState("");
  const [usage, setUsage] = useState({ prompt: 0, completion: 0, total: 0 });
  const [done, setDone] = useState<Record<string, unknown> | null>(null);
  const [error, setError] = useState("");

  const start = async () => {
    setRunning(true);
    setReasoning([]);
    setTools([]);
    setContent("");
    setUsage({ prompt: 0, completion: 0, total: 0 });
    setDone(null);
    setError("");
    const pendingTools = new Map<string, ToolEntry>();
    try {
      await streamAssess(Number(examId), (event: StreamEvent) => {
        if (event.type === "reasoning") {
          setReasoning((prev) => [...prev, event.text]);
        } else if (event.type === "tool_call") {
          pendingTools.set(event.tool, { tool: event.tool, args: event.args });
          setTools(Array.from(pendingTools.values()));
        } else if (event.type === "tool_result") {
          const prev = pendingTools.get(event.tool) ?? { tool: event.tool };
          pendingTools.set(event.tool, { ...prev, hits: event.hits, note: event.note });
          setTools(Array.from(pendingTools.values()));
        } else if (event.type === "content") {
          setContent((prev) => prev + event.delta);
        } else if (event.type === "token_usage") {
          setUsage({ prompt: event.prompt, completion: event.completion, total: event.total });
        } else if (event.type === "done") {
          setDone(event as unknown as Record<string, unknown>);
        } else if (event.type === "error") {
          setError(event.message);
        }
      });
    } catch (e) {
      setError(e instanceof Error ? e.message : "流式判定失败");
    } finally {
      setRunning(false);
    }
  };

  return (
    <div className="flex flex-col gap-3">
      <div className="card p-4">
        <h1 className="text-xl font-bold">{t("nav.assess")}</h1>
        <div className="mt-3 flex gap-2">
          <input
            className="w-48 border border-line px-2 py-2 text-sm"
            placeholder="体检记录 examId"
            value={examId}
            onChange={(e) => setExamId(e.target.value)}
          />
          <button className="btn-primary px-4 py-2 text-sm font-semibold" disabled={running || !examId} onClick={start}>
            {running ? t("common.loading") : t("assess.stream")}
          </button>
        </div>
        {error && <div className="mt-2 text-sm text-red-700">{error}</div>}
      </div>
      <ReasoningFold texts={reasoning} />
      <div>
        <h2 className="mb-1 text-sm font-bold">{t("assess.tools")}</h2>
        <ToolList entries={tools} />
      </div>
      <div className="card min-h-24 p-3">
        <div className={`whitespace-pre-wrap text-sm leading-7 ${running && !done ? "stream-caret" : ""}`}>
          {content || <span className="text-muted">等待正文输出…</span>}
        </div>
      </div>
      <UsageBar prompt={usage.prompt} completion={usage.completion} total={usage.total} />
      {done && (
        <div className="card p-3 text-sm">
          <div>
            结论：<strong>{String(done["conclusionLabel"] ?? done["conclusion"] ?? "")}</strong>
          </div>
          <a className="text-brand" href={`/reports/${String(done["assessmentId"])}`}>
            查看完整报告 #{String(done["assessmentId"])}
          </a>
        </div>
      )}
    </div>
  );
}
