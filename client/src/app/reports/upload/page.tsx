"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { AlertTriangle, CheckCircle2, FileUp, ShieldCheck } from "lucide-react";

import { Badge } from "@/components/ui/badge";
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

interface ExtractedItem {
  id: number;
  itemName: string;
  itemCode: string;
  valueNum: number | null;
  valueText: string;
  unit: string;
  confidence: number;
  confirmed: boolean;
}

interface UploadView {
  uploadId: number;
  fileName: string;
  status: string;
  extractNote: string;
  examId: number | null;
  personId: number | null;
  hazardCode: string;
  items: ExtractedItem[];
}

interface UploadRow {
  uploadId: number;
  fileName: string;
  status: string;
  hazardCode: string;
  personId: number | null;
  extractNote: string;
  createdAt: string;
}

/** 低置信阈值：低于该值提示重点核对。 */
const LOW_CONFIDENCE = 0.6;

export default function ReportUploadPage() {
  const { t } = useI18n();
  const [hazards, setHazards] = useState<Hazard[]>([]);
  const [hazardCode, setHazardCode] = useState("");
  const [personId, setPersonId] = useState("");
  const [examDate, setExamDate] = useState(new Date().toISOString().slice(0, 10));
  const [view, setView] = useState<UploadView | null>(null);
  const [recent, setRecent] = useState<UploadRow[]>([]);
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const fileRef = useRef<HTMLInputElement>(null);

  const reloadRecent = useCallback(
    () =>
      apiFetch<{ data: UploadRow[] }>("/report-uploads?limit=10")
        .then((data) => setRecent(data.data ?? []))
        .catch(() => setRecent([])),
    [],
  );

  useEffect(() => {
    apiFetch<{ data: Hazard[] }>("/hazards")
      .then((data) => setHazards(data.data ?? []))
      .catch(() => setHazards([]));
    reloadRecent();
  }, [reloadRecent]);

  /** 上传并抽取：仅生成草稿，不写体检数据。 */
  const upload = async () => {
    const file = fileRef.current?.files?.[0];
    if (!file) return;
    setError("");
    setMessage("");
    setBusy(true);
    try {
      const form = new FormData();
      form.append("file", file);
      const query = new URLSearchParams();
      if (hazardCode) query.set("hazardCode", hazardCode);
      if (personId) query.set("personId", personId);
      const path = query.toString() ? `/report-uploads?${query.toString()}` : "/report-uploads";
      const data = await apiFetch<UploadView>(path, { method: "POST", body: form });
      setView(data);
      setMessage(t("upload.extracted"));
      reloadRecent();
    } catch (e) {
      setError(e instanceof Error ? e.message : t("upload.failed"));
    } finally {
      setBusy(false);
    }
  };

  const patchItem = (index: number, patch: Partial<ExtractedItem>) => {
    setView((prev) =>
      prev
        ? { ...prev, items: prev.items.map((item, i) => (i === index ? { ...item, ...patch } : item)) }
        : prev,
    );
  };

  /** 保存核对结果：整体覆盖该上传的核对项。 */
  const saveReview = async () => {
    if (!view) return;
    setError("");
    setMessage("");
    setBusy(true);
    try {
      const items = view.items.map((item) => ({
        itemName: item.itemName,
        itemCode: item.itemCode,
        valueNum: item.valueNum,
        valueText: item.valueText,
        unit: item.unit,
        confidence: item.confidence,
        confirmed: item.confirmed,
      }));
      const data = await apiFetch<UploadView>(`/report-uploads/${view.uploadId}/confirm`, {
        method: "POST",
        body: JSON.stringify(items),
      });
      setView(data);
      setMessage(t("upload.confirmed"));
    } catch (e) {
      setError(e instanceof Error ? e.message : t("upload.confirmFailed"));
    } finally {
      setBusy(false);
    }
  };

  /** 确认入库：仅写入已采纳项，经后端再次过滤。 */
  const importToExam = async () => {
    if (!view) return;
    setError("");
    setMessage("");
    setBusy(true);
    try {
      const data = await apiFetch<{ examId: number }>(`/report-uploads/${view.uploadId}/import`, {
        method: "POST",
        body: JSON.stringify({
          personId: personId ? Number(personId) : view.personId,
          examDate,
        }),
      });
      setMessage(t("upload.imported", { id: data.examId }));
      reloadRecent();
    } catch (e) {
      setError(e instanceof Error ? e.message : t("upload.importFailed"));
    } finally {
      setBusy(false);
    }
  };

  const acceptedCount = view?.items.filter((item) => item.confirmed).length ?? 0;

  return (
    <ScrollArea className="h-full">
      <div className="flex w-full flex-col gap-3 p-4">
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <FileUp className="size-4 text-primary" />
              {t("upload.title")}
            </CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col gap-3">
            <p className="text-sm text-muted-foreground">{t("upload.intro")}</p>
            <div className="grid grid-cols-1 gap-3 md:grid-cols-3">
              <label className="text-sm">
                {t("upload.hazard")}
                <select
                  className="mt-1 h-9 w-full border border-input bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                  value={hazardCode}
                  onChange={(e) => setHazardCode(e.target.value)}
                >
                  <option value="">{t("upload.hazardPlaceholder")}</option>
                  {hazards.map((hazard) => (
                    <option key={hazard.code} value={hazard.code}>
                      {hazard.name}（{hazard.category}）
                    </option>
                  ))}
                </select>
              </label>
              <label className="text-sm">
                {t("upload.person")}
                <Input
                  className="mt-1"
                  value={personId}
                  placeholder={t("upload.personPlaceholder")}
                  onChange={(e) => setPersonId(e.target.value.replace(/\D/g, ""))}
                />
              </label>
              <label className="text-sm">
                {t("upload.date")}
                <Input
                  className="mt-1"
                  type="date"
                  value={examDate}
                  onChange={(e) => setExamDate(e.target.value)}
                />
              </label>
            </div>
            <div className="flex flex-wrap items-center gap-2">
              <input
                ref={fileRef}
                type="file"
                accept="image/png,image/jpeg,image/webp"
                className="text-sm file:mr-2 file:border file:bg-background file:px-2 file:py-1 file:text-xs"
              />
              <Button size="sm" onClick={upload} disabled={busy}>
                <FileUp />
                {busy ? t("upload.extracting") : t("upload.start")}
              </Button>
            </div>
            {view && (
              <div className="flex items-center gap-2 text-sm">
                <Badge variant={view.status === "FAILED" ? "destructive" : "success"}>
                  {view.status}
                </Badge>
                <span className="text-muted-foreground">
                  {t("upload.note")}: {view.extractNote}
                </span>
              </div>
            )}
            {message && <div className="text-sm text-emerald-700">{message}</div>}
            {error && <div className="text-sm text-destructive">{error}</div>}
          </CardContent>
        </Card>

        {view && view.items.length > 0 && (
          <Card>
            <CardHeader className="flex-row items-center justify-between space-y-0">
              <CardTitle>{t("upload.items")}</CardTitle>
              <div className="flex items-center gap-2">
                <Badge variant="outline">
                  {t("upload.keep")} {acceptedCount}/{view.items.length}
                </Badge>
                <Button variant="outline" size="sm" onClick={saveReview} disabled={busy}>
                  <CheckCircle2 />
                  {t("upload.confirm")}
                </Button>
                <Button size="sm" onClick={importToExam} disabled={busy || acceptedCount === 0}>
                  <ShieldCheck />
                  {t("upload.import")}
                </Button>
              </div>
            </CardHeader>
            <CardContent className="flex flex-col gap-2">
              <div className="hidden grid-cols-[auto_2fr_1fr_1fr_1fr_6rem] gap-2 text-xs text-muted-foreground md:grid">
                <span>{t("upload.keep")}</span>
                <span>{t("exam.itemName")}</span>
                <span>{t("exam.value")}</span>
                <span>{t("exam.text")}</span>
                <span>{t("exam.unit")}</span>
                <span>{t("upload.confidence")}</span>
              </div>
              {view.items.map((item, index) => {
                const low = item.confidence !== null && Number(item.confidence) < LOW_CONFIDENCE;
                return (
                  <div
                    key={item.id}
                    className="grid grid-cols-1 items-center gap-2 border-b border-border py-1 md:grid-cols-[auto_2fr_1fr_1fr_1fr_6rem]"
                  >
                    <input
                      type="checkbox"
                      className="size-4 accent-primary"
                      checked={item.confirmed}
                      onChange={(e) => patchItem(index, { confirmed: e.target.checked })}
                      title={item.confirmed ? t("upload.keep") : t("upload.drop")}
                    />
                    <Input
                      value={item.itemName}
                      onChange={(e) => patchItem(index, { itemName: e.target.value })}
                    />
                    <Input
                      value={item.valueNum ?? ""}
                      onChange={(e) =>
                        patchItem(index, {
                          valueNum: e.target.value === "" ? null : Number(e.target.value),
                        })
                      }
                    />
                    <Input
                      value={item.valueText}
                      onChange={(e) => patchItem(index, { valueText: e.target.value })}
                    />
                    <Input
                      value={item.unit}
                      onChange={(e) => patchItem(index, { unit: e.target.value })}
                    />
                    <div className="flex items-center gap-1 text-xs">
                      {low ? (
                        <span className="flex items-center gap-1 text-amber-600">
                          <AlertTriangle className="size-3" />
                          {Number(item.confidence).toFixed(2)}
                        </span>
                      ) : (
                        <span className="text-muted-foreground">
                          {Number(item.confidence).toFixed(2)}
                        </span>
                      )}
                    </div>
                  </div>
                );
              })}
              <div className="text-xs text-muted-foreground">{t("upload.disclaimer")}</div>
            </CardContent>
          </Card>
        )}

        <Card>
          <CardHeader>
            <CardTitle>{t("upload.recent")}</CardTitle>
          </CardHeader>
          <CardContent>
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b text-left text-xs text-muted-foreground">
                  <th className="py-2">ID</th>
                  <th>{t("upload.title")}</th>
                  <th>{t("batch.status")}</th>
                  <th>{t("upload.hazard")}</th>
                </tr>
              </thead>
              <tbody>
                {recent.map((row) => (
                  <tr
                    key={row.uploadId}
                    className="cursor-pointer border-b transition-colors hover:bg-accent/50"
                    onClick={() =>
                      apiFetch<UploadView>(`/report-uploads/${row.uploadId}`)
                        .then((data) => setView(data))
                        .catch(() => undefined)
                    }
                  >
                    <td className="py-2 font-mono text-xs">#{row.uploadId}</td>
                    <td>{row.fileName}</td>
                    <td>
                      <Badge variant={row.status === "FAILED" ? "destructive" : "outline"}>
                        {row.status}
                      </Badge>
                    </td>
                    <td className="text-xs text-muted-foreground">{row.hazardCode || "-"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            {recent.length === 0 && (
              <div className="py-4 text-sm text-muted-foreground">{t("common.empty")}</div>
            )}
          </CardContent>
        </Card>
      </div>
    </ScrollArea>
  );
}
