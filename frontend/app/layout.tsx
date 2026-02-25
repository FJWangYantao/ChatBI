import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "ChatBI - 智能数据分析平台",
  description: "基于 AI 的数据分析与可视化平台",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="zh-CN" suppressHydrationWarning>
      <body className="antialiased bg-background text-foreground">
        {children}
      </body>
    </html>
  );
}
