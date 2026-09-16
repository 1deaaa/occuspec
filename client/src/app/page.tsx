"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { apiFetch } from "@/lib/api";
import { useI18n } from "@/i18n/provider";

interface AssessRow {
  assessmentId: number;
  examId: number;
  conclusion: string;
  conclusionLabel: string;
  reviewStatus: string;
  totalTokens: number;
  createdAt: string;
}

export default function WorkspacePage() {
  const { t } = useI18n();
  const [rows, setRows] = useState<AssessRow[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    apiFetch<{ data: AssessRow[]; pagination: unknown }>("/assessments?page=1&pageSize=10}")
      .then((d) => setRows(d.data ?? []))
      .catch(() => setRows([]))
      .finally(() => setLoading(false));
  }, []);

  return (
    <div className="flex flex-col gap-3">
      <div className="card p-4">
        <h1 className="text-xl font-bold">{t("nav.workspace")}</h1>
        <p className="mt-1 text-sm text-muted">{t("app.disclaimer")}</p>
        <div className="mt-3 flex gap-2">
          <Link href="/exams" className="btn-primary px-3 py-2 text-sm">
            {t("nav.exams")}
          </Link>
          <Link href="/assess" className="transition-sharp border border-line bg-white px-3 py-2 text-sm">
            {t("nav.assess")}
          </Link>
        </div>
      </div>
      <div className="card p-4">
        <h2 className="text-sm font-bold">最近判定</h2>
        {loading ? (
          <div className="mt-2 text-sm text-muted">{t("common.loading")}</div>
        ) : rows.length === 0 ? (
          <div className="mt-2 text-sm text-muted">{t("common.empty")}</div>
        ) : (
          <table className="mt-2 w-full text-sm">
            <thead>
              <tr className="border-b border-line text-left text-muted">
                <th className="py-1">评估</th>
                <th>结论</th>
                <th>复核</th>
                <th>用量</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr key={row.assessmentId} className="row-hover border-b border-line">
                  <td className="py-1">
                    <Link className="text-brand" href={`/reports/${row.assessmentId}`}>
                      #{row.assessmentId}
                    </Link>
                  </td>
                  <td>{row.conclusionLabel}</td>
                  <td>{row.reviewStatus === "REVIEWED" ? t("common.reviewed") : t("common.reviewPending")}</td>
                  <td>{row.totalTokens}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}
