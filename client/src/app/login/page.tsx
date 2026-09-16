"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { Activity, LockKeyhole } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { useI18n } from "@/i18n/provider";
import { apiFetch, setToken } from "@/lib/api";

export default function LoginPage() {
  const { t } = useI18n();
  const router = useRouter();
  const [username, setUsername] = useState("admin");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

  const submit = async () => {
    setLoading(true);
    setError("");
    try {
      const data = await apiFetch<{ token: string }>("/auth/login", {
        method: "POST",
        body: JSON.stringify({ username, password }),
      });
      setToken(data.token);
      router.push("/");
    } catch (e) {
      setError(e instanceof Error ? e.message : t("login.failed"));
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="flex h-full items-center justify-center p-4">
      <Card className="w-full max-w-sm">
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <Activity className="size-5 text-primary" />
            {t("app.shortTitle")}
          </CardTitle>
          <CardDescription>{t("app.disclaimer")}</CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-3">
          <label className="text-sm">
            {t("login.username")}
            <Input className="mt-1" value={username} onChange={(e) => setUsername(e.target.value)} />
          </label>
          <label className="text-sm">
            {t("login.password")}
            <Input
              className="mt-1"
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              onKeyDown={(e) => e.key === "Enter" && submit()}
            />
          </label>
          {error && <div className="text-sm text-destructive">{error}</div>}
          <Button className="mt-1 w-full" onClick={submit} disabled={loading}>
            <LockKeyhole />
            {loading ? t("common.loading") : t("login.title")}
          </Button>
          <div className="text-xs text-muted-foreground">{t("login.hint")}</div>
        </CardContent>
      </Card>
    </div>
  );
}
