/** @type {import('next').NextConfig} */
const nextConfig = {
  // 增加代理超时时间（Agent 流程耗时较长，默认30秒不够）
  experimental: {
    proxyTimeout: 300000, // 5分钟（毫秒）
  },
  async rewrites() {
    return [
      {
        source: '/api/mcp/:path*',
        destination: 'http://localhost:8004/api/:path*',
      },
      {
        source: '/api/:path*',
        destination: 'http://localhost:8080/api/:path*',
      },
    ]
  },
}

module.exports = nextConfig
