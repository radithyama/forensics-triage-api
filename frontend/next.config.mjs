/** @type {import('next').NextConfig} */
const nextConfig = {
  // Static export: served by nginx, which also reverse-proxies /api to the gateway.
  output: "export",
  images: { unoptimized: true },
  reactStrictMode: true,
};

export default nextConfig;
