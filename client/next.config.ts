import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  async rewrites() {
    const base = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://127.0.0.1:8080/api/v1";
    return [
      {
        source: "/backend/:path*",
        destination: `${base}/:path*`,
      },
    ];
  },
};

export default nextConfig;
