"use client";

import { useRef, useState } from "react";
import { Layers, Upload } from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { ScrollArea } from "@/components/ui/scroll-area";
import { apiFetch, getToken } from "@/lib/api";
import { useI18n } from "@/i18n/provider";

interface TaskView {
  taskId: number;
  type: string;
  status: string;
  total: number;
  success: number;
  fail: number;
}

export default function BatchPage() {
  const { t } = useI18n();
  const [tasks, setTasks] = useState<TaskView[]>([]);
  const [taskId, setTaskId] = useState("");
  const [message, setMessage] = useState("");
  const fileRef = useRef<HTMLInputElement>(null);

  const reload = () =>
    apiFetch<{ data: TaskView[] }>("/batch-tasks?page=1&pageSize=20")
      .then((data) => setTasks(data.data ?? []))
      .catch(() => setTasks([]));

  const upload = async () => {
    const file = fileRef.current?.files?.[0];
    if (!file) return;
    setMessage("");
    const form = new FormData();
    form.append("file", file);
    try {
      const data = await apiFetch<{ taskId: number }>("/batch-tasks/upload-csv", {
        method: "POST",
        body: form,
      });
      setTaskId(String(data.taskId));
      setMessage(t("batch.uploaded", { id: data.taskId }));
      reload();
    } catch (e) {
      setMessage(e instanceof Error ? e.message : t("batch.uploadFailed"));
    }
  };

  /** Excel 导出走原生下载，需带 token 头，故用 fetch + blob。 */
  const exportXlsx = async (id: string) => {
    const token = getToken();
    const resp = await fetch(`/backend/batch-tasks/${id}/export-xlsx`, {
      headers: token ? { satoken: token } : {},
    });
    if (!resp.ok) {
      setMessage(t("batch.exportFailed"));
      return;
    }
    const blob = await resp.blob();
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = `batch-${id}.xlsx`;
    anchor.click();
    URL.revokeObjectURL(url);
  };

  return (
    <ScrollArea className="h-full">
      <div className="flex w-full flex-col gap-3 p-4">
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <Layers className="size-4 text-primary" />
              {t("nav.batch")}
            </CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col gap-2">
            <div className="flex flex-wrap items-center gap-2">
              <input
                ref={fileRef}
                type="file"
                accept=".csv"
                className="text-sm file:mr-2 file:border file:bg-background file:px-2 file:py-1 file:text-xs"
              />
              <Button size="sm" onClick={upload}>
                <Upload />
                {t("batch.upload")}
              </Button>
            </div>
            <div className="flex flex-wrap items-center gap-2">
              <Input
                className="max-w-xs"
                placeholder={t("batch.taskIdPlaceholder")}
                value={taskId}
                onChange={(e) => setTaskId(e.target.value)}
              />
              <Button
                variant="outline"
                size="sm"
                onClick={async () => {
                  if (!taskId) return;
                  try {
                    const data = await apiFetch<TaskView>(`/batch-tasks/${taskId}`);
                    setMessage(
                      `${t("batch.status")}: ${data.status} (${data.success}/${data.total})`,
                    );
                  } catch (e) {
                    setMessage(e instanceof Error ? e.message : t("batch.queryFailed"));
                  }
                }}
              >
                {t("batch.queryStatus")}
              </Button>
              <Button variant="outline" size="sm" onClick={() => taskId && exportXlsx(taskId)}>
                {t("action.export")}
              </Button>
            </div>
            {message && <div className="text-sm text-muted-foreground">{message}</div>}
          </CardContent>
        </Card>

        <Button variant="outline" size="sm" className="self-start" onClick={reload}>
          {t("batch.refreshList")}
        </Button>

        <Card>
          <CardContent className="pt-4">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b text-left text-xs text-muted-foreground">
                  <th className="py-2">ID</th>
                  <th>{t("batch.status")}</th>
                  <th>{t("batch.total")}</th>
                  <th>{t("batch.success")}</th>
                  <th>{t("batch.fail")}</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {tasks.map((task) => (
                  <tr key={task.taskId} className="border-b transition-colors ease-sharp hover:bg-accent/50">
                    <td className="py-2 font-mono text-xs">#{task.taskId}</td>
                    <td>
                      <Badge
                        variant={
                          task.status === "SUCCESS" ? "success" : task.status === "FAILED" ? "destructive" : "warning"
                        }
                      >
                        {task.status}
                      </Badge>
                    </td>
                    <td>{task.total}</td>
                    <td>{task.success}</td>
                    <td>{task.fail}</td>
                    <td>
                      <Button variant="ghost" size="sm" onClick={() => exportXlsx(String(task.taskId))}>
                        {t("action.export")}
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            {tasks.length === 0 && <div className="py-4 text-sm text-muted-foreground">{t("common.empty")}</div>}
          </CardContent>
        </Card>
      </div>
    </ScrollArea>
  );
}
