"use client";

import { Coins } from "lucide-react";

/** 用量条：展示上游实际统计的 token 数。 */
export function UsageBar({
  prompt,
  completion,
  total,
}: {
  prompt: number;
  completion: number;
  total: number;
}) {
  return (
    <div className="card flex items-center gap-3 px-3 py-2 text-xs text-muted">
      <Coins size={14} className="text-brand" />
      <span>输入 {prompt}</span>
      <span>输出 {completion}</span>
      <span className="font-semibold text-ink">合计 {total}</span>
      <span>tokens（上游实际统计）</span>
    </div>
  );
}
