"use client";

import { useEffect, useState } from "react";
import { ClipboardList, Plus, Trash2 } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { ScrollArea } from "@/components/ui/scroll-area";
import { apiFetch } from "@/lib/api";
import { useI18n } from "@/i18n/provider";

interface Hazard {
  code: string;
  name: string;
  category: string;
}

/** 检查项行：可自由增删、自定义名称，不再固定枚举。 */
interface ItemRow {
  itemCode: string;
  itemName: string;
  valueNum?: number;
  valueText?: string;
  unit?: string;
}

let rowSeq = 0;

function emptyRow(): ItemRow {
  rowSeq += 1;
  return { itemCode: `item_${rowSeq}_${Date.now()}`, itemName: "", unit: "" };
}

export default function ExamsPage() {
  const { t } = useI18n();
  const [hazards, setHazards] = useState<Hazard[]>([]);
  const [name, setName] = useState("");
  const [hazardCode, setHazardCode] = useState("");
  const [examDate, setExamDate] = useState(new Date().toISOString().slice(0, 10));
  const [items, setItems] = useState<ItemRow[]>([emptyRow()]);
  const [result, setResult] = useState("");
  const [error, setError] = useState("");
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    apiFetch<{ data: Hazard[] }>("/hazards")
      .then((data) => {
        const list = data.data ?? [];
        setHazards(list);
        if (list.length > 0) setHazardCode((prev) => prev || list[0].code);
      })
      .catch(() => setHazards([]));
  }, []);

  const patchRow = (index: number, patch: Partial<ItemRow>) => {
    setItems((prev) => prev.map((row, i) => (i === index ? { ...row, ...patch } : row)));
  };

  const submit = async () => {
    setError("");
    setResult("");
    const filled = items.filter(
      (item) => item.itemName.trim() !== "" && (item.valueNum !== undefined || (item.valueText ?? "") !== ""),
    );
    if (filled.length === 0) {
      setError(t("exam.needOneItem"));
      return;
    }
    setSubmitting(true);
    try {
      const person = await apiFetch<{ personId: number }>("/persons", {
        method: "POST",
        body: JSON.stringify({ name: name || t("exam.anonymous"), exposureHistory: hazardCode }),
      });
      const exam = await apiFetch<{ examId: number }>("/exams", {
        method: "POST",
        headers: { "Idempotency-Key": `exam-${Date.now()}` },
        body: JSON.stringify({
          personId: person.personId,
          hazardCode,
          examDate,
          items: filled.map((item) => ({
            itemCode: item.itemCode,
            itemName: item.itemName,
            valueNum: item.valueNum ?? null,
            valueText: item.valueText ?? "",
            unit: item.unit ?? "",
          })),
        }),
      });
      setResult(t("exam.created", { id: exam.examId }));
    } catch (e) {
      setError(e instanceof Error ? e.message : t("exam.submitFailed"));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <ScrollArea className="h-full">
      <div className="flex w-full flex-col gap-3 p-4">
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <ClipboardList className="size-4 text-primary" />
              {t("nav.exams")}
            </CardTitle>
          </CardHeader>
          <CardContent className="grid grid-cols-1 gap-3 md:grid-cols-3">
            <label className="text-sm">
              {t("exam.person")}
              <Input
                className="mt-1"
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder={t("exam.personPlaceholder")}
              />
            </label>
            <label className="text-sm">
              {t("exam.hazard")}
              <select
                className="mt-1 h-9 w-full border border-input bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                value={hazardCode}
                onChange={(e) => setHazardCode(e.target.value)}
              >
                {hazards.map((hazard) => (
                  <option key={hazard.code} value={hazard.code}>
                    {hazard.name}（{hazard.category}）
                  </option>
                ))}
              </select>
            </label>
            <label className="text-sm">
              {t("exam.date")}
              <Input className="mt-1" type="date" value={examDate} onChange={(e) => setExamDate(e.target.value)} />
            </label>
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="flex-row items-center justify-between space-y-0">
            <CardTitle>{t("exam.items")}</CardTitle>
            <Button variant="outline" size="sm" onClick={() => setItems((prev) => [...prev, emptyRow()])}>
              <Plus />
              {t("action.addRow")}
            </Button>
          </CardHeader>
          <CardContent className="flex flex-col gap-2">
            {/* 表头 */}
            <div className="hidden grid-cols-[2fr_1fr_1fr_1fr_auto] gap-2 text-xs text-muted-foreground md:grid">
              <span>{t("exam.itemName")}</span>
              <span>{t("exam.value")}</span>
              <span>{t("exam.text")}</span>
              <span>{t("exam.unit")}</span>
              <span />
            </div>
            {items.map((item, index) => (
              <div
                key={item.itemCode}
                className="slide-in grid grid-cols-1 gap-2 md:grid-cols-[2fr_1fr_1fr_1fr_auto]"
              >
                <Input
                  placeholder={t("exam.itemNamePlaceholder")}
                  value={item.itemName}
                  onChange={(e) => patchRow(index, { itemName: e.target.value })}
                />
                <Input
                  placeholder={t("exam.value")}
                  value={item.valueNum ?? ""}
                  onChange={(e) =>
                    patchRow(index, { valueNum: e.target.value === "" ? undefined : Number(e.target.value) })
                  }
                />
                <Input
                  placeholder={t("exam.text")}
                  value={item.valueText ?? ""}
                  onChange={(e) => patchRow(index, { valueText: e.target.value })}
                />
                <Input
                  placeholder={t("exam.unit")}
                  value={item.unit ?? ""}
                  onChange={(e) => patchRow(index, { unit: e.target.value })}
                />
                <Button
                  variant="ghost"
                  size="icon"
                  onClick={() => setItems((prev) => prev.filter((_, i) => i !== index))}
                  disabled={items.length <= 1}
                  title={t("action.removeRow")}
                >
                  <Trash2 />
                </Button>
              </div>
            ))}
            {error && <div className="text-sm text-destructive">{error}</div>}
            {result && <div className="text-sm text-emerald-700">{result}</div>}
            <div>
              <Button onClick={submit} disabled={submitting}>
                {submitting ? t("common.loading") : t("action.submit")}
              </Button>
            </div>
          </CardContent>
        </Card>
      </div>
    </ScrollArea>
  );
}
