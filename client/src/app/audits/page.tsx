"use client";

import { useState } from "react";
import { History } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { ScrollArea } from "@/components/ui/scroll-area";
import { apiFetch } from "@/lib/api";
import { useI18n } from "@/i18n/provider";

export default function AuditsPage() {
  const { t } = useI18n();
  const [assessmentId, setAssessmentId] = useState("");
  const [rows, setRows] = useState<Record<string, unknown>[]>([]);
  const [error, setError] = useState("");

  return (
    <ScrollArea className="h-full">
      <div className="mx-auto w-full max-w-4xl p-4">
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <History className="size-4 text-primary" />
              {t("nav.audits")}
            </CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col gap-3">
            <div className="flex gap-2">
              <Input
                className="max-w-xs"
                placeholder={t("audits.assessIdPlaceholder")}
                value={assessmentId}
                onChange={(e) => setAssessmentId(e.target.value)}
              />
              <Button
                onClick={async () => {
                  setError("");
                  try {
                    const data = await apiFetch<{ audits: Record<string, unknown>[] }>(
                      `/audits/assessments/replay?assessmentId=${assessmentId}`,
                    );
                    setRows(data.audits ?? []);
                  } catch (e) {
                    setError(e instanceof Error ? e.message : t("audits.failed"));
                    setRows([]);
                  }
                }}
              >
                {t("action.search")}
              </Button>
            </div>
            {error && <div className="text-sm text-destructive">{error}</div>}
            {rows.map((row, index) => (
              <div key={index} className="border bg-muted/40 p-2 text-sm">
                <div className="font-medium">{String(row["action"])}</div>
                <div className="text-xs text-muted-foreground">
                  {String(row["createdAt"])} · trace {String(row["traceId"])} · {String(row["costMs"])}ms
                </div>
                <div className="mt-0.5 text-xs">{String(row["diff"] ?? "")}</div>
              </div>
            ))}
            {rows.length === 0 && !error && (
              <div className="text-sm text-muted-foreground">{t("audits.hint")}</div>
            )}
          </CardContent>
        </Card>
      </div>
    </ScrollArea>
  );
}
