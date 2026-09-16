"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { CornerDownLeft, MessageSquarePlus, Square } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { ScrollArea } from "@/components/ui/scroll-area";
import { ReasoningFold } from "@/components/chat/reasoning-fold";
import { CitationList, ToolTraceList } from "@/components/chat/tool-trace-list";
import { UsageBar } from "@/components/chat/usage-bar";
import { useI18n } from "@/i18n/provider";
import {
  apiFetch,
  streamChat,
  type ChatMessageView,
  type ChatSessionView,
  type Citation,
  type ToolTrace,
} from "@/lib/api";
import { cn } from "@/lib/utils";

/** 引用去重：同标准同条款只保留一条。 */
function mergeCitations(existing: Citation[], incoming: Citation[]): Citation[] {
  const seen = new Set(existing.map((c) => `${c.standardCode}#${c.clauseNo}`));
  const merged = [...existing];
  for (const citation of incoming) {
    const key = `${citation.standardCode}#${citation.clauseNo}`;
    if (!seen.has(key)) {
      seen.add(key);
      merged.push(citation);
    }
  }
  return merged;
}

/** 消息视图：流式过程中 reasoning/toolTraces/content 均为增量累积。 */
interface Message {
  role: "user" | "assistant";
  content: string;
  reasoning: string;
  toolTraces: ToolTrace[];
  citations: Citation[];
  usage?: { prompt: number; completion: number; total: number };
  streaming?: boolean;
}

export default function ChatPage() {
  const { t } = useI18n();
  const [sessions, setSessions] = useState<ChatSessionView[]>([]);
  const [sessionId, setSessionId] = useState<number | null>(null);
  const [messages, setMessages] = useState<Message[]>([]);
  const [input, setInput] = useState("");
  const [streaming, setStreaming] = useState(false);
  const abortRef = useRef<AbortController | null>(null);
  const bottomRef = useRef<HTMLDivElement>(null);

  /** 拉取会话列表：供对话完成后刷新与手动刷新复用。 */
  const loadSessions = useCallback(
    () => apiFetch<{ data: ChatSessionView[] }>("/chat/sessions").then((data) => setSessions(data.data ?? [])),
    [],
  );

  // 首屏加载会话列表：在回调里 setState，避免 effect 内同步更新
  useEffect(() => {
    apiFetch<{ data: ChatSessionView[] }>("/chat/sessions")
      .then((data) => setSessions(data.data ?? []))
      .catch(() => setSessions([]));
  }, []);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth", block: "end" });
  }, [messages]);

  const openSession = async (id: number) => {
    setSessionId(id);
    try {
      const data = await apiFetch<{ data: ChatMessageView[] }>(`/chat/sessions/${id}/messages`);
      setMessages(
        (data.data ?? []).map((m) => ({
          role: m.role === "user" ? "user" : "assistant",
          content: m.content ?? "",
          reasoning: m.reasoning ?? "",
          toolTraces: (m.toolCalls as ToolTrace[] | null) ?? [],
          citations: (m.citations as Citation[] | null) ?? [],
          usage: m.totalTokens ? { prompt: 0, completion: 0, total: m.totalTokens } : undefined,
        })),
      );
    } catch {
      setMessages([]);
    }
  };

  const send = async () => {
    const text = input.trim();
    if (!text || streaming) return;
    setInput("");
    setStreaming(true);
    // 先落用户消息与流式助手占位，后续增量原地更新
    setMessages((prev) => [
      ...prev,
      { role: "user", content: text, reasoning: "", toolTraces: [], citations: [] },
      { role: "assistant", content: "", reasoning: "", toolTraces: [], citations: [], streaming: true },
    ]);
    const abort = new AbortController();
    abortRef.current = abort;
    const patchLast = (patch: (msg: Message) => Message) =>
      setMessages((prev) => prev.map((m, i) => (i === prev.length - 1 ? patch(m) : m)));
    try {
      await streamChat(
        text,
        sessionId,
        (event) => {
          if (event.type === "session") {
            setSessionId(event.sessionId);
          } else if (event.type === "reasoning") {
            patchLast((m) => ({ ...m, reasoning: m.reasoning + event.text }));
          } else if (event.type === "tool_call") {
            patchLast((m) => ({
              ...m,
              toolTraces: [...m.toolTraces, { tool: event.tool, args: event.args, hits: 0, note: "" }],
            }));
          } else if (event.type === "tool_result") {
            patchLast((m) => {
              const traces = [...m.toolTraces];
              for (let i = traces.length - 1; i >= 0; i--) {
                if (traces[i].tool === event.tool && traces[i].hits === 0) {
                  traces[i] = { ...traces[i], hits: event.hits, note: event.note };
                  break;
                }
              }
              return { ...m, toolTraces: traces, citations: mergeCitations(m.citations, event.citations) };
            });
          } else if (event.type === "content") {
            patchLast((m) => ({ ...m, content: m.content + event.delta }));
          } else if (event.type === "token_usage") {
            patchLast((m) => ({
              ...m,
              usage: { prompt: event.prompt, completion: event.completion, total: event.total },
            }));
          } else if (event.type === "done") {
            patchLast((m) => ({
              ...m,
              streaming: false,
              citations: event.citations.length > 0 ? event.citations : m.citations,
            }));
            loadSessions();
          } else if (event.type === "error") {
            patchLast((m) => ({ ...m, streaming: false, content: m.content || event.message }));
          }
        },
        abort.signal,
      );
    } catch (error) {
      const message = error instanceof Error ? error.message : "对话失败";
      patchLast((m) => ({ ...m, streaming: false, content: m.content || message }));
    } finally {
      patchLast((m) => ({ ...m, streaming: false }));
      setStreaming(false);
      abortRef.current = null;
    }
  };

  const stop = () => {
    abortRef.current?.abort();
    setStreaming(false);
  };

  return (
    <div className="flex h-full">
      {/* 会话列表 */}
      <div className="flex w-60 shrink-0 flex-col border-r bg-card">
        <div className="border-b p-2">
          <Button
            variant="outline"
            size="sm"
            className="w-full justify-start"
            onClick={() => {
              setSessionId(null);
              setMessages([]);
            }}
          >
            <MessageSquarePlus />
            {t("action.newChat")}
          </Button>
        </div>
        <ScrollArea className="flex-1">
          <div className="p-1">
            {sessions.length === 0 ? (
              <div className="p-2 text-xs text-muted-foreground">{t("chat.emptySessions")}</div>
            ) : (
              sessions.map((session) => (
                <button
                  key={session.sessionId}
                  type="button"
                  onClick={() => openSession(session.sessionId)}
                  className={cn(
                    "block w-full truncate px-2 py-1.5 text-left text-xs transition-colors ease-sharp hover:bg-accent",
                    sessionId === session.sessionId ? "bg-accent font-medium" : "",
                  )}
                  title={session.title}
                >
                  {session.title || `#${session.sessionId}`}
                </button>
              ))
            )}
          </div>
        </ScrollArea>
      </div>

      {/* 对话主区：占满剩余空间，输入框吸底 */}
      <div className="flex min-w-0 flex-1 flex-col">
        <ScrollArea className="min-h-0 flex-1">
          <div className="mx-auto w-full max-w-4xl px-4 py-4">
            {messages.length === 0 ? (
              <div className="border bg-card p-4 text-sm leading-6 text-muted-foreground">
                {t("chat.welcome")}
              </div>
            ) : (
              <div className="flex flex-col gap-4">
                {messages.map((message, index) => (
                  <div key={index} className={cn(message.role === "user" ? "flex justify-end" : "flex justify-start")}>
                    <div className={cn("min-w-0", message.role === "user" ? "max-w-[80%]" : "w-full")}>
                      {message.role === "user" ? (
                        <div className="bg-primary px-3 py-2 text-sm text-primary-foreground whitespace-pre-wrap">
                          {message.content}
                        </div>
                      ) : (
                        <div className="flex flex-col gap-2">
                          <ReasoningFold text={message.reasoning} streaming={message.streaming} />
                          <ToolTraceList traces={message.toolTraces} streaming={message.streaming} />
                          {(message.content || message.streaming) && (
                            <div className="border bg-card p-3 text-sm leading-7 whitespace-pre-wrap">
                              <span className={message.streaming ? "stream-caret" : ""}>{message.content}</span>
                            </div>
                          )}
                          <CitationList citations={message.citations} />
                          {message.usage && (
                            <UsageBar
                              prompt={message.usage.prompt}
                              completion={message.usage.completion}
                              total={message.usage.total}
                            />
                          )}
                        </div>
                      )}
                    </div>
                  </div>
                ))}
              </div>
            )}
            <div ref={bottomRef} />
          </div>
        </ScrollArea>

        {/* 输入区：吸底，Enter 发送，Shift+Enter 换行 */}
        <div className="shrink-0 border-t bg-card p-3">
          <div className="mx-auto flex w-full max-w-4xl items-end gap-2">
            <Textarea
              value={input}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Enter" && !e.shiftKey) {
                  e.preventDefault();
                  send();
                }
              }}
              placeholder={t("chat.placeholder")}
              className="min-h-[44px] max-h-40 resize-none"
              disabled={streaming}
            />
            {streaming ? (
              <Button variant="outline" size="icon" onClick={stop} title={t("action.stop")}>
                <Square />
              </Button>
            ) : (
              <Button size="icon" onClick={send} disabled={!input.trim()} title={t("action.send")}>
                <CornerDownLeft />
              </Button>
            )}
          </div>
          <div className="mx-auto mt-1 w-full max-w-4xl text-[11px] text-muted-foreground">
            {t("app.disclaimer")}
          </div>
        </div>
      </div>
    </div>
  );
}
