import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  /**
   * 不再用 rewrites 代理后端。
   *
   * rewrites 会把响应整体缓冲后一次性回传，SSE（text/event-stream）经过它之后
   * 退化为"等全部生成完再一次性到达"，前端拿不到流式增量。
   * 改由 app/backend/[...path]/route.ts 的 Route Handler 透传流（见该文件注释）。
   */
};

export default nextConfig;
