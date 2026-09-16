"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { useI18n } from "@/i18n/provider";
import { apiFetch, setToken } from "@/lib/api";

export default function LoginPage() {
  const { t } = useI18n();
  const router = useRouter();
  const [username, setUsername] = useState("admin");
  const [password, setPassword] = useState("1009");
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

  return (
    <div className="card mx-auto mt-10 max-w-sm p-6">
      <h1 className="text-xl font-bold">{t("login.title")}</h1>
      <p className="mt-1 text-xs text-muted">{t("app.disclaimer")}</p>
      <label className="mt-4 block text-sm">
        {t("login.username")}
        <input
          className="transition-sharp mt-1 w-full border border-line bg-white px-2 py-2"
          value={username}
          onChange={(e) => setUsername(e.target.value)}
        />
      </label>
      <label className="mt-3 block text-sm">
        {t("login.password")}
        <input
          type="password"
          className="transition-sharp mt-1 w-full border border-line bg-white px-2 py-2"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
        />
      </label>
      {error && <div className="mt-3 border border-line bg-paper px-2 py-1 text-sm text-red-700">{error}</div>}
      <button
        className="btn-primary mt-4 w-full py-2 text-sm font-semibold"
        disabled={loading}
        onClick={async () => {
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
            setError(e instanceof Error ? e.message : "登录失败");
          } finally {
            setLoading(false);
          }
        }}
      >
        {loading ? t("common.loading") : t("login.title")}
      </button>
    </div>
  );
}
