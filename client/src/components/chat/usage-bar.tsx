import { Coins } from "lucide-react";

import { useI18n } from "@/i18n/provider";

/** 用量条：展示上游实际统计的 token 数。 */
export function UsageBar({
  prompt,
  completion,
  total,
}: {
  prompt?: number;
  completion?: number;
  total: number;
}) {
  const { t } = useI18n();
  return (
    <div className="mt-1 flex items-center gap-2 text-[11px] text-muted-foreground">
      <Coins className="size-3" />
      {prompt !== undefined && (
        <span>
          {t("chat.inputTokens")} {prompt}
        </span>
      )}
      {completion !== undefined && (
        <span>
          {t("chat.outputTokens")} {completion}
        </span>
      )}
      <span className="font-medium text-foreground">
        {total} {t("common.tokens")}
      </span>
    </div>
  );
}
