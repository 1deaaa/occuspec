"use client";

import { Streamdown } from "streamdown";
import "streamdown/styles.css";

import { cn } from "@/lib/utils";

/**
 * AI 输出渲染：用 Streamdown 渲染流式 Markdown。
 *
 * <p>为什么不用 react-markdown：流式输出会在任意位置截断，
 * 传统解析器遇到"半个加粗"“未闭合代码块”会瞬间渲染成错乱结构再跳变。
 * Streamdown 会在渲染前补齐未闭合语法（remend），避免闪烁。
 *
 * <p>`isAnimating` 驱动逐词淡入与光标，与后端 SSE 的逐增量推送配合，
 * 形成"边生成边出现"的观感；流结束后传 false，停止动画并稳定排版。
 */
export function MarkdownContent({
  children,
  isAnimating = false,
  className,
}: {
  children: string;
  isAnimating?: boolean;
  className?: string;
}) {
  if (!children) return null;
  return (
    <Streamdown
      className={cn(
        // 项目为锐利直角风格，这里收紧默认留白并统一行高
        "text-sm leading-6 [&_h1]:mb-2 [&_h1]:mt-3 [&_h1]:text-base [&_h1]:font-semibold",
        "[&_h2]:mb-2 [&_h2]:mt-3 [&_h2]:text-sm [&_h2]:font-semibold",
        "[&_h3]:mb-1 [&_h3]:mt-2 [&_h3]:text-sm [&_h3]:font-medium",
        "[&_p]:my-1.5 [&_ul]:my-1.5 [&_ul]:list-disc [&_ul]:pl-5",
        "[&_ol]:my-1.5 [&_ol]:list-decimal [&_ol]:pl-5 [&_li]:my-0.5",
        "[&_strong]:font-semibold [&_a]:text-primary [&_a]:underline",
        "[&_blockquote]:my-2 [&_blockquote]:border-l-2 [&_blockquote]:border-primary/40 [&_blockquote]:pl-3",
        "[&_code]:bg-muted [&_code]:px-1 [&_code]:py-0.5 [&_code]:text-[0.85em]",
        "[&_table]:my-2 [&_table]:w-full [&_table]:text-xs",
        "[&_th]:border [&_th]:border-border [&_th]:bg-muted [&_th]:px-2 [&_th]:py-1 [&_th]:text-left",
        "[&_td]:border [&_td]:border-border [&_td]:px-2 [&_td]:py-1",
        className,
      )}
      animated
      isAnimating={isAnimating}
      parseIncompleteMarkdown
    >
      {children}
    </Streamdown>
  );
}
