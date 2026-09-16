"use client";

import { useState } from "react";
import { apiFetch } from "@/lib/api";
import { useI18n } from "@/i18n/provider";

export default function AuditsPage() {
  const { t } = useI18n();
  const [bizId, setBizId] = useState("");
  const [rows, setRows] = useState<Record<string, unknown>[]>([]);

  return (
    <div className="card p-4">
      <h1 className="text-xl font-bold">{t("nav.audits")}</h1>
      <div className="mt-2 flex gap-2">
        <input
          className="w-48 border border-line px-2 py-2 text-sm"
          placeholder="评估 assessmentId"
          value={bizId}
          onChange={(e) => setBizId(e.target.value)}
        />
        <button
          className="btn-primary px-4 py-2 text-sm"
          onClick={async () => {
            const data = await apiFetch<{ audits: Record<string, unknown>[] }>(
              `/audits/assessments/replay?assessmentId=${bizId}`,
            );
            setRows(data.audits ?? []);
          }}
        >
          {t("action.search")}
        </button>
      </div>
      <div className="mt-3 flex flex-col gap-2">
        {rows.map((row, idx) => (
          <div key={idx} className="border border-line bg-paper p-2 text-sm">
            <div className="font-semibold">{String(row["action"])}</div>
            <div className="text-xs text-muted">
              {String(row["createdAt"])} · trace {String(row["traceId"])} · {String(row["costMs"])}ms
            </div>
            <div className="text-xs">{String(row["diff"] ?? "")}</div>
          </div>
        ))}
      </div>
    </div>
  );
}
