"use client";

import { useEffect, useState } from "react";
import { AlertTriangle, FileSearch, Stethoscope } from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { ScrollArea } from "@/components/ui/scroll-area";
import { UsageBar } from "@/components/chat/usage-bar";
import { apiFetch } from "@/lib/api";
import { useI18n } from "@/i18n/provider";

interface Report {
  assessmentId: number;
  conclusionLabel: string;
  conclusionSource: string;
  reviewStatus: string;
  reviewComment: string;
  evidences: { standardCode: string; clauseNo: string; quote: string; reason: string }[];
  standardRecommendations: { itemCode: string; itemName: string; reason: string; sourceClauseNo: string }[];
  extendedRecommendations: { itemCode: string; itemName: string; reason: string }[];
  usage: { prompt: number; completion: number; total: number; costMs: number };
  disclaimer: string;
}

export default function ReportDetailPage({ params }: { params: { id: string } }) {
  const { t } = useI18n();
  const [report, setReport] = useState<Report | null>(null);
  const [error, setError] = useState("");

  const load = () =>
    apiFetch<Report>(`/assessments/${params.id}/report`)
      .then(setReport)
      .catch((e) => setError(e instanceof Error ? e.message : t("reports.loadFailed")));

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [params.id]);

  if (error) return <div className="p-4 text-sm text-destructive">{error}</div>;
  if (!report) return <div className="p-4 text-sm text-muted-foreground">{t("common.loading")}</div>;

  return (
    <ScrollArea className="h-full">
      <div className="mx-auto flex w-full max-w-4xl flex-col gap-3 p-4">
        {/* 结论区：显著标注复核要求 */}
        <Card className="border-l-4 border-l-primary">
          <CardHeader>
            <CardTitle className="flex flex-wrap items-center gap-2">
              <Stethoscope className="size-4 text-primary" />
              {t("reports.reportNo")} #{report.assessmentId} · {report.conclusionLabel}
              <Badge variant={report.reviewStatus === "REVIEWED" ? "success" : "warning"}>
                {report.reviewStatus === "REVIEWED" ? t("common.reviewed") : t("common.reviewPending")}
              </Badge>
              <Badge variant="outline">{report.conclusionSource}</Badge>
            </CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col gap-2">
            <div className="flex items-start gap-2 border border-amber-300 bg-amber-50 p-2 text-sm text-amber-900">
              <AlertTriangle className="mt-0.5 size-4 shrink-0" />
              {report.disclaimer}
            </div>
            <div>
              <Button
                size="sm"
                onClick={async () => {
                  await apiFetch(`/assessments/${params.id}/review`, {
                    method: "POST",
                    body: JSON.stringify({ reviewerId: "chief", comment: "" }),
                  });
                  load();
                }}
              >
                {t("action.review")}
              </Button>
            </div>
          </CardContent>
        </Card>

        {/* 证据链 */}
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <FileSearch className="size-4 text-primary" />
              {t("report.evidences")}
            </CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col gap-2">
            {report.evidences.map((evidence, index) => (
              <div key={index} className="border-l-2 border-l-primary bg-muted/40 p-2 text-sm">
                <div className="font-medium text-primary">
                  {evidence.standardCode} · {evidence.clauseNo}
                </div>
                <p className="mt-1 whitespace-pre-wrap leading-6">{evidence.quote}</p>
                <p className="mt-1 text-xs text-muted-foreground">{evidence.reason}</p>
              </div>
            ))}
            {report.evidences.length === 0 && (
              <div className="text-sm text-muted-foreground">{t("common.empty")}</div>
            )}
          </CardContent>
        </Card>

        {/* 推荐分开展示：标准内 与 机构扩展 */}
        <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
          <Card>
            <CardHeader>
              <CardTitle className="text-sm">{t("report.standardRecs")}</CardTitle>
            </CardHeader>
            <CardContent className="text-sm">
              {report.standardRecommendations.length === 0 ? (
                <span className="text-muted-foreground">{t("common.empty")}</span>
              ) : (
                report.standardRecommendations.map((rec, index) => (
                  <div key={index} className="border-b py-1 last:border-b-0">
                    {rec.itemName}（{rec.itemCode}）
                    <div className="text-xs text-muted-foreground">
                      {rec.reason} · {rec.sourceClauseNo}
                    </div>
                  </div>
                ))
              )}
            </CardContent>
          </Card>
          <Card>
            <CardHeader>
              <CardTitle className="text-sm">{t("report.extendedRecs")}</CardTitle>
            </CardHeader>
            <CardContent className="text-sm">
              {report.extendedRecommendations.length === 0 ? (
                <span className="text-muted-foreground">{t("common.empty")}</span>
              ) : (
                report.extendedRecommendations.map((rec, index) => (
                  <div key={index} className="border-b py-1 last:border-b-0">
                    {rec.itemName}
                    <div className="text-xs text-muted-foreground">{rec.reason}</div>
                  </div>
                ))
              )}
            </CardContent>
          </Card>
        </div>

        <UsageBar prompt={report.usage.prompt} completion={report.usage.completion} total={report.usage.total} />
      </div>
    </ScrollArea>
  );
}
