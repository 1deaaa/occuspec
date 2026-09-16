"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { apiFetch } from "@/lib/api";
import { useI18n } from "@/i18n/provider";

interface Row {
  assessmentId: number;
  conclusionLabel: string;
  reviewStatus: string;
  totalTokens: number;
}

export default function ReportsPage() {
  const { t } = useI18n();
  const [rows, setRows] = useState<Row[]>([]);

  useEffect(() => {
    apiFetch<{ data: Row[] }>("/assessments?page=1&pageSize=20")
      .then((d) => setRows(d.data ?? []))
      .catch(() => setRows([]));
  }, []);

  return (
    <div className="card p-4">
      <h1 className="text-xl font-bold">{t("nav.reports")}</h1>
      <table className="mt-3 w-full text-sm">
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
              <td>{row.reviewStatus}</td>
              <td>{row.totalTokens}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
