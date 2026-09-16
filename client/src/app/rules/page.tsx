"use client";

import { useEffect, useState } from "react";
import { apiFetch } from "@/lib/api";
import { useI18n } from "@/i18n/provider";

interface Rule {
  id: number;
  code: string;
  name: string;
  hazardCode: string;
  conclusion: string;
  weight: number;
  enabled: boolean;
  version: string;
}

export default function RulesPage() {
  const { t } = useI18n();
  const [rows, setRows] = useState<Rule[]>([]);

  const reload = () =>
    apiFetch<{ data: Rule[] }>("/rules?page=1&pageSize=50")
      .then((d) => setRows(d.data ?? []))
      .catch(() => setRows([]));

  useEffect(() => {
    reload();
  }, []);

  return (
    <div className="card p-4">
      <h1 className="text-xl font-bold">{t("nav.rules")}</h1>
      <table className="mt-3 w-full text-sm">
        <thead>
          <tr className="border-b border-line text-left text-muted">
            <th className="py-1">编码</th>
            <th>名称</th>
            <th>危害</th>
            <th>权重</th>
            <th>状态</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.id} className="row-hover border-b border-line">
              <td className="py-1 font-mono text-xs">{row.code}</td>
              <td>{row.name}</td>
              <td>{row.hazardCode}</td>
              <td>{row.weight}</td>
              <td>{row.enabled ? "启用" : "停用"}</td>
              <td>
                <button
                  className="text-brand"
                  onClick={async () => {
                    await apiFetch(`/rules/${row.id}/${row.enabled ? "disable" : "enable"}`, { method: "POST" });
                    reload();
                  }}
                >
                  {row.enabled ? "停用" : "启用"}
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
