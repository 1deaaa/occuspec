"use client";

import { useEffect, useState } from "react";
import { apiFetch } from "@/lib/api";
import { useI18n } from "@/i18n/provider";
import { UsageBar } from "@/components/usage-bar";

interface Report {
  assessmentId: number;
  conclusionLabel: string;
  reviewStatus: string;
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

  useEffect(() => {
    apiFetch<Report>(`/assessments/${params.id}/report`)
      .then(setReport)
      .catch((e) => setError(e instanceof Error ? e.message : "加载失败"));
  }, [params.id]);

  if (error) return <div className="card p-4 text-sm text-red-700">{error}</div>;
  if (!report) return <div className="card p-4 text-sm text-muted">{t("common.loading")}</div>;

  return (
    <div className="flex flex-col gap-3">
      <div className="card border-l-4 border-l-brand p-4">
        <h1 className="text-xl font-bold">
          报告 #{report.assessmentId} · {report.conclusionLabel}
        </h1>
        <p className="mt-1 text-sm font-semibold text-red-700">{report.disclaimer}</p>
        <button
          className="btn-primary mt-2 px-3 py-1 text-xs"
          onClick={async () => {
            await apiFetch(`/assessments/${params.id}/review`, {
              method: "POST",
              body: JSON.stringify({ reviewerId: "chief", comment: "已复核" }),
            });
            const next = await apiFetch<Report>(`/assessments/${params.id}/report`);
            setReport(next);
          }}
        >
          {t("action.review")}（{report.reviewStatus}）
        </button>
      </div>
      <div className="card p-4">
        <h2 className="text-sm font-bold">{t("report.evidences")}</h2>
        <div className="mt-2 flex flex-col gap-2">
          {report.evidences.map((e, idx) => (
            <div key={idx} className="border border-line bg-paper p-2 text-sm">
              <div className="font-semibold text-brand">
                {e.standardCode} · {e.clauseNo}
              </div>
              <p className="mt-1 leading-6">{e.quote}</p>
              <p className="mt-1 text-xs text-muted">{e.reason}</p>
            </div>
          ))}
        </div>
      </div>
      <div className="card p-4">
        <h2 className="text-sm font-bold">{t("report.standardRecs")}</h2>
        {report.standardRecommendations.map((r, idx) => (
          <div key={idx} className="mt-1 text-sm">
            {r.itemName}（{r.itemCode}）——{r.reason}
          </div>
        ))}
        <h2 className="mt-3 text-sm font-bold">{t("report.extendedRecs")}</h2>
        {report.extendedRecommendations.length === 0 ? (
          <div className="text-sm text-muted">{t("common.empty")}</div>
        ) : (
          report.extendedRecommendations.map((r, idx) => (
            <div key={idx} className="mt-1 text-sm">
              {r.itemName}——{r.reason}
            </div>
          ))
        )}
      </div>
      <UsageBar prompt={report.usage.prompt} completion={report.usage.completion} total={report.usage.total} />
    </div>
  );
}
