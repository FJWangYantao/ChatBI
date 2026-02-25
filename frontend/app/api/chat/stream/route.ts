/**
 * Next.js Route Handler — 流式代理 SSE 请求到后端
 *
 * Next.js 的 rewrites() 会缓冲整个响应体，不支持 SSE 流式传输。
 * 这个 Route Handler 使用 fetch + ReadableStream 直接透传后端的 SSE 流。
 * Route Handler 优先级高于 rewrites，所以 /api/chat/stream 会命中这里。
 */

export const dynamic = 'force-dynamic';

const BACKEND_URL = process.env.BACKEND_URL || 'http://localhost:8080';

export async function POST(request: Request) {
  const body = await request.json();

  const backendResponse = await fetch(`${BACKEND_URL}/api/chat/stream`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Accept': 'text/event-stream',
    },
    body: JSON.stringify(body),
  });

  if (!backendResponse.ok) {
    return new Response(
      JSON.stringify({ error: `Backend error: ${backendResponse.status}` }),
      { status: backendResponse.status, headers: { 'Content-Type': 'application/json' } }
    );
  }

  if (!backendResponse.body) {
    return new Response('Backend returned no body', { status: 502 });
  }

  // 直接透传后端的 ReadableStream，不做缓冲
  return new Response(backendResponse.body, {
    status: 200,
    headers: {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache, no-transform',
      'Connection': 'keep-alive',
      'X-Accel-Buffering': 'no',
    },
  });
}
