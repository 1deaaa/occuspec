"use client";

import { useCallback, useSyncExternalStore } from "react";

import { getToken, setToken, clearToken } from "./api";

/** 令牌变更订阅者集合，用于跨组件同步登录态。 */
const listeners = new Set<() => void>();

function emit() {
  for (const listener of listeners) {
    listener();
  }
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  // 跨标签页同步：storage 事件由浏览器派发
  const onStorage = () => listener();
  window.addEventListener("storage", onStorage);
  return () => {
    listeners.delete(listener);
    window.removeEventListener("storage", onStorage);
  };
}

const getSnapshot = () => getToken();
/** 服务端渲染无 localStorage，返回空串保持一致。 */
const getServerSnapshot = () => "";

/**
 * 登录令牌状态：以 localStorage 为外部存储，避免在 effect 中同步 setState。
 * 返回令牌与三个操作函数，操作后自动通知所有订阅者。
 */
export function useAuthToken() {
  const token = useSyncExternalStore(subscribe, getSnapshot, getServerSnapshot);

  const save = useCallback((next: string) => {
    setToken(next);
    emit();
  }, []);

  const clear = useCallback(() => {
    clearToken();
    emit();
  }, []);

  return { token, isAuthed: token !== "", save, clear };
}
