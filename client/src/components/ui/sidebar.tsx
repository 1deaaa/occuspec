"use client";

import { useState } from "react";
import { cn } from "@/lib/utils";

/** 可折叠侧边栏：宽度过渡平滑，折叠态仅显图标。 */
export function Sidebar({
  children,
  footer,
  defaultCollapsed = false,
}: {
  children: React.ReactNode;
  footer?: React.ReactNode;
  defaultCollapsed?: boolean;
}) {
  const [collapsed, setCollapsed] = useState(defaultCollapsed);
  return (
    <aside
      className={cn(
        "flex h-full flex-col border-r bg-card transition-[width] duration-300 ease-sharp",
        collapsed ? "w-14" : "w-56",
      )}
    >
      <div className="flex-1 overflow-hidden">{children}</div>
      <div className="border-t p-1">
        <button
          type="button"
          onClick={() => setCollapsed((v) => !v)}
          className="w-full px-2 py-1.5 text-left text-xs text-muted-foreground transition-colors ease-sharp hover:bg-accent hover:text-accent-foreground"
        >
          {collapsed ? "»" : "「 收起"}
        </button>
        {footer}
      </div>
    </aside>
  );
}
