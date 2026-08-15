/**
 * SiliconFlow 免费对话代理（Cloudflare Worker）。
 *
 * 把 OpenAI 兼容的 `POST /v1/chat/completions` 转发到 SiliconFlow，并在服务端注入
 * API Key（存于 Worker Secret `SILICONFLOW_API_KEY`）。App 端与 GitHub 均不出现明文 Key。
 *
 * 安全与配额保护：
 * - 仅放行免费 7B 模型（白名单），防止共享免费配额被调用付费模型滥用；
 * - 未配置 Secret 时返回 500 结构化错误；
 * - 上游错误状态码与消息原样透传（App 端可读）；
 * - SSE 流式响应直接透传（不缓存、不读全文）。
 */

const UPSTREAM = "https://api.siliconflow.cn";
const UPSTREAM_CHAT_PATH = "/v1/chat/completions";

/** 仅允许的免费模型（与 App 内「免费对话」供应商 defaultModel 一致）。 */
const ALLOWED_MODELS = new Set(["Qwen/Qwen2.5-7B-Instruct"]);

const JSON_HEADERS = { "Content-Type": "application/json; charset=utf-8" };

function jsonResponse(body, status) {
  return new Response(JSON.stringify(body), { status, headers: JSON_HEADERS });
}

export default {
  /**
   * @param {Request} request
   * @param {{ SILICONFLOW_API_KEY?: string }} env
   */
  async fetch(request, env) {
    const url = new URL(request.url);

    if (request.method !== "POST" || url.pathname !== UPSTREAM_CHAT_PATH) {
      return jsonResponse({ error: { message: "仅支持 POST /v1/chat/completions" } }, 404);
    }

    const apiKey = env.SILICONFLOW_API_KEY;
    if (!apiKey) {
      return jsonResponse({ error: { message: "服务端未配置 SILICONFLOW_API_KEY，请稍后再试" } }, 500);
    }

    // 读取请求体以校验模型（chat/completions 请求体为 KB 级，可安全读取；响应体绝不读全文）。
    let payload;
    try {
      payload = await request.json();
    } catch {
      return jsonResponse({ error: { message: "请求体不是合法 JSON" } }, 400);
    }

    const model = typeof payload.model === "string" ? payload.model : "";
    if (!ALLOWED_MODELS.has(model)) {
      return jsonResponse(
        {
          error: {
            message: `免费服务仅支持模型：${[...ALLOWED_MODELS].join("、")}（收到：${model || "空"}）`,
          },
        },
        403,
      );
    }

    try {
      const upstream = await fetch(`${UPSTREAM}${UPSTREAM_CHAT_PATH}`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${apiKey}`,
        },
        body: JSON.stringify(payload),
      });

      // 流式透传（SSE 或 JSON），不缓存；上游错误状态/正文原样返回。
      return new Response(upstream.body, {
        status: upstream.status,
        statusText: upstream.statusText,
        headers: {
          "Content-Type": upstream.headers.get("Content-Type") || "application/json",
          "Cache-Control": "no-store",
        },
      });
    } catch (e) {
      return jsonResponse({ error: { message: `上游请求失败：${e.message}` } }, 502);
    }
  },
};
