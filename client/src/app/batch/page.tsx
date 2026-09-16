"use client";

import { useState } from "react";
import { getToken } from "@/lib/api";
import { useI18n } from "@/i18n/provider";

export default function BatchPage() {
  const { t } = useI18n();
  const [taskId, setTaskId] = useState("");
  const [status, setStatus] = useState("");

  const authed = (init?: RequestInit): RequestInit => {
    const headers = new Headers(init?.headers);
    const token = getToken();
    if (token) headers.set("satoken", token);
    return { ...init, headers };
  };

  return (
    <div className="flex flex-col gap-3">
      <div className="card p-4">
        <h1 className="text-xl font-bold">{t("nav.batch")}</h1>
        <input
          type="file"
          accept=".csv"
          className="mt-2 text-sm"
          onChange={async (e) => {
            const file = e.target.files?.[0];
            if (!file) return;
            const form = new FormData();
            form.append("file", file);
            const resp = await fetch("/backend/batch-tasks/upload-csv", authed({ method: "POST", body: form }));
            const data = await resp.json();
            setTaskId(String(data.data?.taskId ?? ""));
            setStatus(JSON.stringify(data.data ?? {}));
          }}
        />
        {status && <div className="mt-2 text-sm">{status}</div>}
      </div>
      <div className="card p-4">
        <div className="flex gap-2">
          <input
            className="w-48 border border-line px-2 py-2 text-sm"
            placeholder="任务 taskId"
            value={taskId}
            onChange={(e) => setTaskId(e.target.value)}
          />
          <button
            className="transition-sharp border border-line bg-white px-3 py-2 text-sm"
            onClick={async () => {
              const resp = await fetch(`/backend/batch-tasks/${taskId}`, authed());
              const data = await resp.json();
              setStatus(JSON.stringify(data.data ?? data));
            }}
          >
            状态轮询
          </button>
          <a className="btn-primary px-3 py-2 text-sm" href={`/backend/batch-tasks/${taskId}/export-xlsx`}>
            {t("action.export")}
          </a>
        </div>
      </div>
    </div>
  );
}
