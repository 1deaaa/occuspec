"use client";

import { useEffect, useState } from "react";
import { apiFetch } from "@/lib/api";
import { useI18n } from "@/i18n/provider";

interface Hazard {
  code: string;
  name: string;
  category: string;
}

interface ExamItem {
  itemCode: string;
  itemName: string;
  valueNum?: number;
  valueText?: string;
  unit?: string;
}

const COMMON_ITEMS: ExamItem[] = [
  { itemCode: "hearing_avg_db", itemName: "双耳高频平均听阈", unit: "dB" },
  { itemCode: "blood_lead_umol", itemName: "血铅", unit: "μmol/L" },
  { itemCode: "wbc", itemName: "白细胞", unit: "10^9/L" },
  { itemCode: "alt", itemName: "谷丙转氨酶", unit: "U/L" },
];

export default function ExamsPage() {
  const { t } = useI18n();
  const [hazards, setHazards] = useState<Hazard[]>([]);
  const [name, setName] = useState("");
  const [hazardCode, setHazardCode] = useState("noise");
  const [examDate, setExamDate] = useState("2026-09-01");
  const [items, setItems] = useState<ExamItem[]>(COMMON_ITEMS);
  const [result, setResult] = useState("");
  const [error, setError] = useState("");

  useEffect(() => {
    apiFetch<{ data: Hazard[] }>("/hazards")
      .then((d) => setHazards(d.data ?? []))
      .catch(() => setHazards([]));
  }, []);

  const setItem = (idx: number, patch: Partial<ExamItem>) => {
    setItems((prev) => prev.map((it, i) => (i === idx ? { ...it, ...patch } : it)));
  };

  return (
    <div className="flex flex-col gap-3">
      <div className="card p-4">
        <h1 className="text-xl font-bold">{t("nav.exams")}</h1>
        <div className="mt-3 grid grid-cols-2 gap-3 text-sm">
          <label>
            {t("exam.person")}
            <input
              className="transition-sharp mt-1 w-full border border-line px-2 py-2"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="姓名（将自动建档）"
            />
          </label>
          <label>
            {t("exam.hazard")}
            <select
              className="mt-1 w-full border border-line bg-white px-2 py-2"
              value={hazardCode}
              onChange={(e) => setHazardCode(e.target.value)}
            >
              {hazards.map((h) => (
                <option key={h.code} value={h.code}>
                  {h.name}
                </option>
              ))}
            </select>
          </label>
          <label>
            {t("exam.date")}
            <input
              type="date"
              className="mt-1 w-full border border-line px-2 py-2"
              value={examDate}
              onChange={(e) => setExamDate(e.target.value)}
            />
          </label>
        </div>
      </div>
      <div className="card p-4">
        <h2 className="text-sm font-bold">{t("exam.items")}</h2>
        <div className="mt-2 flex flex-col gap-2">
          {items.map((item, idx) => (
            <div key={item.itemCode} className="grid grid-cols-4 gap-2 text-sm">
              <span className="py-2">{item.itemName}</span>
              <input
                className="border border-line px-2 py-1"
                placeholder="数值"
                value={item.valueNum ?? ""}
                onChange={(e) =>
                  setItem(idx, { valueNum: e.target.value === "" ? undefined : Number(e.target.value) })
                }
              />
              <input
                className="border border-line px-2 py-1"
                placeholder="文本"
                value={item.valueText ?? ""}
                onChange={(e) => setItem(idx, { valueText: e.target.value })}
              />
              <span className="py-2 text-muted">{item.unit}</span>
            </div>
          ))}
        </div>
        {error && <div className="mt-2 text-sm text-red-700">{error}</div>}
        {result && <div className="mt-2 text-sm text-green-800">{result}</div>}
        <button
          className="btn-primary mt-3 px-4 py-2 text-sm font-semibold"
          onClick={async () => {
            setError("");
            setResult("");
            try {
              const person = await apiFetch<{ personId: number }>("/persons", {
                method: "POST",
                body: JSON.stringify({ name: name || "未命名", exposureHistory: hazardCode }),
              });
              const payload = {
                personId: person.personId,
                hazardCode,
                examDate,
                items: items
                  .filter((it) => it.valueNum !== undefined || (it.valueText ?? "") !== "")
                  .map((it) => ({ ...it, unit: it.unit ?? "" })),
              };
              const exam = await apiFetch<{ examId: number }>("/exams", {
                method: "POST",
                headers: { "Idempotency-Key": `exam-${Date.now()}` },
                body: JSON.stringify(payload),
              });
              setResult(`录入成功，体检记录 #${exam.examId}，可前往智能判定页发起判定。`);
            } catch (e) {
              setError(e instanceof Error ? e.message : "提交失败");
            }
          }}
        >
          {t("action.submit")}
        </button>
      </div>
    </div>
  );
}
