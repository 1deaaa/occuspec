"use client";

const TOKEN_KEY = "occuspec-token";

export function getToken(): string {
  if (typeof window === "undefined") return "";
  return window.localStorage.getItem(TOKEN_KEY) ?? "";
}

export function setToken(token: string) {
  window.localStorage.setItem(TOKEN_KEY, token);
}

export function clearToken() {
  window.localStorage.removeItem(TOKEN_KEY);
}

export interface ApiEnvelope<T> {
  code: number;
  message: string;
  data: T;
  details?: unknown;
}

export async function apiFetch<T>(path: string, init?: RequestInit): Promise<T> {
  const headers = new Headers(init?.headers);
  headers.set("Content-Type", "application/json");
  const token = getToken();
  if (token) headers.set("satoken", token);
  const resp = await fetch(`/backend${path}`, { ...init, headers });
  if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
  const envelope = (await resp.json()) as ApiEnvelope<T>;
  if (envelope.code !== 0) {
    throw new Error(envelope.message || `业务错误 ${envelope.code}`);
  }
  return envelope.data;
}

/** SSE 事件：reasoning/tool_call/tool_result/content/token_usage/done/error */
export type StreamEvent =
  | { type: "reasoning"; text: string }
  | { type: "tool_call"; tool: string; args: Record<string, unknown> }
  | { type: "tool_result"; tool: string; hits: number; note: string }
  | { type: "content"; assessmentId: string; delta: string }
  | { type: "token_usage"; assessmentId: string; prompt: number; completion: number; total: number }
  | { type: "done"; assessmentId: number; conclusion: string; conclusionLabel: string; evidences: unknown[]; recommendations: unknown[] }
  | { type: "error"; code: string; message: string };

/** 流式判定：fetch 读取 SSE，逐事件回调。 */
export async function streamAssess(
  examId: number,
  onEvent: (event: StreamEvent) => void,
  signal?: AbortSignal,
): Promise<void> {
  const headers = new Headers({ "Content-Type": "application/json", Accept: "text/event-stream" });
  const token = getToken();
  if (token) headers.set("satoken", token);
  const resp = await fetch("/backend/assessments/stream", {
    method: "POST",
    headers,
    body: JSON.stringify({ examId }),
    signal,
  });
  if (!resp.ok || !resp.body) throw new Error(`HTTP ${resp.status}`);
  const reader = resp.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  const dispatch = (rawEvent: string, rawData: string) => {
    const event = rawEvent.trim() || "message";
    let payload: Record<string, unknown> = {};
    try {
      payload = rawData ? (JSON.parse(rawData) as Record<string, unknown>) : {};
    } catch {
      payload = { text: rawData };
    }
    onEvent({ type: event, ...payload } as StreamEvent);
  };
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    let idx: number;
    while ((idx = buffer.indexOf("\n\n")) >= 0) {
      const chunk = buffer.slice(0, idx);
      buffer = buffer.slice(idx + 2);
      const lines = chunk.split("\n");
      let event = "";
      const dataLines: string[] = [];
      for (const line of lines) {
        if (line.startsWith("event:")) event = line.slice(6).trim();
        else if (line.startsWith("data:")) dataLines.push(line.slice(5).trim());
      }
      dispatch(event, dataLines.join("\n"));
    }
  }
}
