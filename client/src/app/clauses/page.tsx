"use client";

import { useState } from "react";
import { apiFetch } from "@/lib/api";
import { useI18n } from "@/i18n/provider";

interface Clause {
  id: number;
  standardCode: string;
  clauseNo: string;
  title: string;
  pageNo: number;
  quote: string;
}

export default function ClausesPage() {
  const { t } = useI18n();
  const [keyword, setKeyword] = useState("噪声");
  const [rows, setRows] = useState<Clause[]>([]);
  const [detail, setDetail] = useState<Record<string, unknown> | null>(null);

  const search = async () => {
    const data = await apiFetch<{ data: Clause[] }>(
      `/clauses?keyword=${encodeURIComponent(keyword)}&page=1&pageSize=10`,
    );
    setRows(data.data ?? []);
    setDetail(null);
  };

  return (
    <div className="flex flex-col gap-3">
      <div className="card p-4">
        <h1 className="text-xl font-bold">{t("nav.clauses")}</h1>
        <div className="mt-2 flex gap-2">
          <input
            className="w-64 border border-line px-2 py-2 text-sm"
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
          />
          <button className="btn-primary px-4 py-2 text-sm" onClick={search}>
            {t("action.search")}
          </button>
        </div>
      </div>
      <div className="grid grid-cols-2 gap-3">
        <div className="card p-2">
          {rows.map((row) => (
            <button
              key={row.id}
              className="row-hover block w-full border-b border-line px-2 py-2 text-left text-sm"
              onClick={async () => {
                const data = await apiFetch<Record<string, unknown>>(`/clauses/detail?id=${row.id}`);
                setDetail(data);
              }}
            >
              <span className="font-semibold text-brand">
                {row.standardCode} · {row.clauseNo}
              </span>{" "}
              {row.title}
            </button>
          ))}
        </div>
        <div className="card max-h-[32rem] overflow-auto p-3 text-sm leading-7">
          {detail ? (
            <>
              <div className="font-bold">
                {String(detail["standardCode"])} · {String(detail["clauseNo"])}（第 {String(detail["pageNo"])} 页）
              </div>
              <pre className="mt-2 whitespace-pre-wrap">{String(detail["content"] ?? "")}</pre>
            </>
          ) : (
            <span className="text-muted">点击左侧条款查看原文</span>
          )}
        </div>
      </div>
    </div>
  );
}
