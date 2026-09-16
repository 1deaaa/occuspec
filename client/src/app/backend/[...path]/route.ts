import type { NextRequest } from "next/server";

/**
 * 后端代理（Route Handler）。
 *
 * <p>为什么不用 next.config 的 rewrites：rewrites 会把上游响应缓冲后一次性回传，
 * SSE 经它之后退化为"全部生成完才到达"，前端拿不到流式增量（实测 28KB 响应
 * 作为单个 chunk 在 44 秒时一次性到达）。
 *
 * <p>这里直接把上游响应体作为 ReadableStream 透传，配合 Next.js 的流式响应，
 * 事件可实时到达浏览器。同时显式关闭缓存与响应缓冲。
 */

const BACKEND_BASE =
  process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://127.0.0.1:8080/api/v1";

/** 透传的请求头白名单：只转发业务需要的，避免传递 hop-by-hop 头。 */
const FORWARD_REQUEST_HEADERS = [
  "content-type",
  "accept",
  "satoken",
  "idempotency-key",
  "authorization",
];

async function proxy(request: NextRequest, path: string[]): Promise<Response> {
  const target = `${BACKEND_BASE}/${path.join("/")}${request.nextUrl.search}`;

  const headers = new Headers();
  for (const name of FORWARD_REQUEST_HEADERS) {
    const value = request.headers.get(name);
    if (value) headers.set(name, value);
  }

  // GET/HEAD 不带 body，其余原样透传（含 multipart 文件流）
  const hasBody = request.method !== "GET" && request.method !== "HEAD";
  const upstream = await fetch(target, {
    method: request.method,
    headers,
    body: hasBody ? request.body : undefined,
    // Node fetch 需要显式声明 duplex 才能流式发送请求体
    // @ts-expect-error duplex 尚未进入 TS 类型定义
    duplex: hasBody ? "half" : undefined,
    redirect: "manual",
    cache: "no-store",
  });

  // 原样回传上游响应头，保留 Content-Type（含 text/event-stream）与状态码
  const responseHeaders = new Headers();
  const contentType = upstream.headers.get("content-type");
  if (contentType) responseHeaders.set("content-type", contentType);
  responseHeaders.set("cache-control", "no-cache, no-transform");
  // 关闭可能存在的中间层缓冲
  responseHeaders.set("x-accel-buffering", "no");

  return new Response(upstream.body, {
    status: upstream.status,
    headers: responseHeaders,
  });
}

export async function GET(
  request: NextRequest,
  ctx: { params: Promise<{ path: string[] }> },
): Promise<Response> {
  const { path } = await ctx.params;
  return proxy(request, path);
}

export async function POST(
  request: NextRequest,
  ctx: { params: Promise<{ path: string[] }> },
): Promise<Response> {
  const { path } = await ctx.params;
  return proxy(request, path);
}

export async function PUT(
  request: NextRequest,
  ctx: { params: Promise<{ path: string[] }> },
): Promise<Response> {
  const { path } = await ctx.params;
  return proxy(request, path);
}

export async function DELETE(
  request: NextRequest,
  ctx: { params: Promise<{ path: string[] }> },
): Promise<Response> {
  const { path } = await ctx.params;
  return proxy(request, path);
}
