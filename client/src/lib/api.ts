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
  if (!(init?.body instanceof FormData)) {
    headers.set("Content-Type", "application/json");
  }
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

/** 引用条款。 */
export interface Citation {
  standardCode: string;
  clauseNo: string;
  title?: string;
  pageNo?: number | null;
  quote?: string;
}

/** 工具调用记录。 */
export interface ToolTrace {
  tool: string;
  args?: Record<string, unknown>;
  hits: number;
  note: string;
}

/**
 * 雪花主键类型：后端按 JS 安全整数范围判定，超范围的 ID 序列化为字符串。
 * 前端一律按 string 处理并在比较时用字符串相等，避免 Number 转换丢精度。
 */
export type SnowflakeId = string;

/** 对话流式事件。 */
export type ChatEvent =
  | { type: "session"; sessionId: SnowflakeId }
  | { type: "reasoning"; text: string }
  | { type: "tool_call"; tool: string; args: Record<string, unknown> }
  | { type: "tool_result"; tool: string; hits: number; note: string; citations: Citation[] }
  | { type: "content"; delta: string }
  | { type: "token_usage"; prompt: number; completion: number; total: number }
  | { type: "done"; sessionId: SnowflakeId; citations: Citation[]; toolTraces: ToolTrace[]; rounds: number }
  | { type: "error"; code: string; message: string };

/**
 * 读取 SSE 响应流并按事件回调；非事件流（如未登录的 JSON 错误体）则抛出带错误码的异常。
 * 两端流式接口共用，避免重复实现。
 */
async function readSseStream(
  path: string,
  body: unknown,
  onEvent: (type: string, payload: Record<string, unknown>) => void,
  signal?: AbortSignal,
): Promise<void> {
  const headers = new Headers({
    // 同时接受事件流与 JSON：鉴权失败等场景后端返回统一 JSON 错误体
    "Content-Type": "application/json",
    Accept: "text/event-stream, application/json",
  });
  const token = getToken();
  if (token) headers.set("satoken", token);
  const resp = await fetch(`/backend${path}`, {
    method: "POST",
    headers,
    body: JSON.stringify(body),
    signal,
  });
  const contentType = resp.headers.get("content-type") ?? "";
  // 后端出错（未登录/参数错）时返回 JSON，需按统一错误体解析，不能按事件流读
  if (contentType.includes("application/json")) {
    const envelope = (await resp.json()) as ApiEnvelope<unknown>;
    throw new ApiError(envelope.code, envelope.message || `HTTP ${resp.status}`);
  }
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
      payload = { delta: rawData, text: rawData };
    }
    onEvent(event, payload);
  };
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    let idx: number;
    while ((idx = buffer.indexOf("\n\n")) >= 0) {
      const chunk = buffer.slice(0, idx);
      buffer = buffer.slice(idx + 2);
      let event = "";
      const dataLines: string[] = [];
      for (const line of chunk.split("\n")) {
        if (line.startsWith("event:")) event = line.slice(6).trim();
        else if (line.startsWith("data:")) dataLines.push(line.slice(5).trim());
      }
      dispatch(event, dataLines.join("\n"));
    }
  }
}

/**
 * 流式对话：fetch 读取 SSE 逐事件回调。
 * content/reasoning 为增量文本，由调用方拼接。
 */
export async function streamChat(
  message: string,
  sessionId: SnowflakeId | null,
  onEvent: (event: ChatEvent) => void,
  signal?: AbortSignal,
): Promise<void> {
  await readSseStream(
    "/chat/stream",
    sessionId ? { message, sessionId } : { message },
    (type, payload) => onEvent({ type, ...payload } as ChatEvent),
    signal,
  );
}

/** 带业务错误码的异常，便于调用方区分未登录等场景。 */
export class ApiError extends Error {
  readonly code: number;

  constructor(code: number, message: string) {
    super(message);
    this.name = "ApiError";
    this.code = code;
  }
}

/** 会话消息历史。 */
export interface ChatMessageView {
  role: string;
  content: string;
  reasoning?: string | null;
  toolCalls?: ToolTrace[] | null;
  citations?: Citation[] | null;
  totalTokens?: number;
  createdAt?: string;
}

/** 会话摘要。 */
export interface ChatSessionView {
  sessionId: SnowflakeId;
  title: string;
  updatedAt: string;
}

/** 旧版同步判定流（专业模式保留）。 */
export type StreamEvent =
  | { type: "reasoning"; text: string }
  | { type: "tool_call"; tool: string; args: Record<string, unknown> }
  | { type: "tool_result"; tool: string; hits: number; note: string }
  | { type: "content"; assessmentId: SnowflakeId; delta: string }
  | { type: "token_usage"; assessmentId: SnowflakeId; prompt: number; completion: number; total: number }
  | { type: "done"; assessmentId: SnowflakeId; conclusion: string; conclusionLabel: string; evidences: unknown[]; recommendations: unknown[] }
  | { type: "error"; code: string; message: string };

/** 流式判定：体检记录 → 结论（专业模式）。 */
export async function streamAssess(
  examId: SnowflakeId,
  onEvent: (event: StreamEvent) => void,
  signal?: AbortSignal,
): Promise<void> {
  await readSseStream(
    "/assessments/stream",
    { examId },
    (type, payload) => onEvent({ type, ...payload } as StreamEvent),
    signal,
  );
}
