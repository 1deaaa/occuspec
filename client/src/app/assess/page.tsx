"use client";

import { useState } from "react";
import { FlaskConical } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { ScrollArea } from "@/components/ui/scroll-area";
import { ReasoningFold } from "@/components/chat/reasoning-fold";
import { ToolTraceList } from "@/components/chat/tool-trace-list";
import { UsageBar } from "@/components/chat/usage-bar";
import { streamAssess, type StreamEvent } from "@/lib/api";
import { useI18n } from "@/i18n/provider";

export default function AssessPage() {
  const { t } = useI18n();
  const [examId, setExamId] = useState("");
  const [running, setRunning] = useState(false);
  const [reasoning, setReasoning] = useState("");
  const [traces, setTraces] = useState<{ tool: string; args?: Record<string, unknown>; hits: number; note: string }[]>([]);
  const [content, setContent] = useState("");
  const [usage, setUsage] = useState({ prompt: 0, completion: 0, total: 0 });
  const [done, setDone] = useState<Record<string, unknown> | null>(null);
  const [error, setError] = useState("");

  const start = async () => {
    setRunning(true);
    setReasoning("");
    setTraces([]);
    setContent("");
    setUsage({ prompt: 0, completion: 0, total: 0 });
    setDone(null);
    setError("");
    try {
      await streamAssess(Number(examId), (event: StreamEvent) => {
        if (event.type === "reasoning") {
          setReasoning((prev) => prev + event.text);
        } else if (event.type === "tool_call") {
          setTraces((prev) => [...prev, { tool: event.tool, args: event.args, hits: 0, note: "" }]);
        } else if (event.type === "tool_result") {
          setTraces((prev) => {
            const next = [...prev];
            for (let i = next.length - 1; i >= 0; i--) {
              if (next[i].tool === event.tool && next[i].hits === 0) {
                next[i] = { ...next[i], hits: event.hits, note: event.note };
                break;
              }
            }
            return next;
          });
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
      setError(e instanceof Error ? e.message : t("assess.failed"));
    } finally {
      setRunning(false);
    }
  };

  return (
    <ScrollArea className="h-full">
      <div className="mx-auto flex w-full max-w-4xl flex-col gap-3 p-4">
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <FlaskConical className="size-4 text-primary" />
              {t("nav.assess")}
            </CardTitle>
          </CardHeader>
          <CardContent className="flex gap-2">
            <Input
              className="max-w-xs"
              placeholder={t("assess.examIdPlaceholder")}
              value={examId}
              onChange={(e) => setExamId(e.target.value)}
            />
            <Button disabled={running || !examId} onClick={start}>
              {running ? t("common.loading") : t("assess.stream")}
            </Button>
          </CardContent>
        </Card>

        {error && <div className="border border-destructive bg-card p-2 text-sm text-destructive">{error}</div>}
        <ReasoningFold text={reasoning} streaming={running && !done} />
        <ToolTraceList traces={traces} streaming={running && !done} />
        {(content || running) && (
          <Card>
            <CardContent className="pt-4 text-sm leading-7 whitespace-pre-wrap">
              <span className={running && !done ? "stream-caret" : ""}>{content}</span>
            </CardContent>
          </Card>
        )}
        {(usage.total > 0 || done) && <UsageBar prompt={usage.prompt} completion={usage.completion} total={usage.total} />}
        {done && (
          <Card>
            <CardHeader>
              <CardTitle>{String(done["conclusionLabel"] ?? done["conclusion"] ?? "")}</CardTitle>
            </CardHeader>
            <CardContent>
              <a className="text-sm text-primary hover:underline" href={`/reports/${String(done["assessmentId"])}`}>
                {t("assess.viewReport")} #{String(done["assessmentId"])}
              </a>
            </CardContent>
          </Card>
        )}
      </div>
    </ScrollArea>
  );
}
