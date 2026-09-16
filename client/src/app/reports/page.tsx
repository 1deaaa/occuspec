"use client";

import { useEffect, useState } from "react";
import { FileText } from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { ScrollArea } from "@/components/ui/scroll-area";
import { apiFetch, type SnowflakeId } from "@/lib/api";
import { useI18n } from "@/i18n/provider";

interface Row {
  assessmentId: SnowflakeId;
  examId: SnowflakeId;
  conclusion: string;
  conclusionLabel: string;
  reviewStatus: string;
  totalTokens: number;
  createdAt: string;
}

export default function ReportsPage() {
  const { t } = useI18n();
  const [rows, setRows] = useState<Row[]>([]);

  useEffect(() => {
    apiFetch<{ data: Row[] }>("/assessments?page=1&pageSize=50")
      .then((data) => setRows(data.data ?? []))
      .catch(() => setRows([]));
  }, []);

  return (
    <ScrollArea className="h-full">
      <div className="w-full p-4">
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <FileText className="size-4 text-primary" />
              {t("nav.reports")}
            </CardTitle>
          </CardHeader>
          <CardContent>
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b text-left text-xs text-muted-foreground">
                  <th className="py-2">ID</th>
                  <th>{t("reports.conclusion")}</th>
                  <th>{t("reports.review")}</th>
                  <th>{t("reports.tokens")}</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <tr key={row.assessmentId} className="border-b transition-colors ease-sharp hover:bg-accent/50">
                    <td className="py-2 font-mono text-xs">#{row.assessmentId}</td>
                    <td>{row.conclusionLabel}</td>
                    <td>
                      <Badge variant={row.reviewStatus === "REVIEWED" ? "success" : "warning"}>
                        {row.reviewStatus === "REVIEWED" ? t("common.reviewed") : t("common.reviewPending")}
                      </Badge>
                    </td>
                    <td>{row.totalTokens}</td>
                    <td>
                      <a className="text-primary hover:underline" href={`/reports/${row.assessmentId}`}>
                        {t("reports.detail")}
                      </a>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            {rows.length === 0 && <div className="py-4 text-sm text-muted-foreground">{t("common.empty")}</div>}
          </CardContent>
        </Card>
      </div>
    </ScrollArea>
  );
}
