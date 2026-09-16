"use client";

import { useEffect, useState } from "react";
import { SlidersHorizontal } from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { ScrollArea } from "@/components/ui/scroll-area";
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
      .then((data) => setRows(data.data ?? []))
      .catch(() => setRows([]));

  useEffect(() => {
    reload();
  }, []);

  return (
    <ScrollArea className="h-full">
      <div className="w-full p-4">
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <SlidersHorizontal className="size-4 text-primary" />
              {t("nav.rules")}
            </CardTitle>
          </CardHeader>
          <CardContent>
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b text-left text-xs text-muted-foreground">
                  <th className="py-2">{t("rules.code")}</th>
                  <th>{t("rules.name")}</th>
                  <th>{t("rules.hazard")}</th>
                  <th>{t("rules.weight")}</th>
                  <th>{t("rules.status")}</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {rows.map((rule) => (
                  <tr key={rule.id} className="border-b transition-colors ease-sharp hover:bg-accent/50">
                    <td className="py-2 font-mono text-xs">{rule.code}</td>
                    <td>{rule.name}</td>
                    <td className="text-xs">{rule.hazardCode || "-"}</td>
                    <td>{rule.weight}</td>
                    <td>
                      <Badge variant={rule.enabled ? "success" : "outline"}>
                        {rule.enabled ? t("rules.enabled") : t("rules.disabled")}
                      </Badge>
                    </td>
                    <td>
                      <Button
                        variant="ghost"
                        size="sm"
                        onClick={async () => {
                          await apiFetch(`/rules/${rule.id}/${rule.enabled ? "disable" : "enable"}`, {
                            method: "POST",
                          });
                          reload();
                        }}
                      >
                        {rule.enabled ? t("rules.disable") : t("rules.enable")}
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            {rows.length === 0 && <div className="py-4 text-sm text-muted-foreground">{t("common.empty")}</div>}
          </CardContent>
        </Card>
      </div>
    </ScrollArea>
  );
}
