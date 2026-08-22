package com.chatbyyourside.config

/**
 * 规范化云端 LLM Base URL（幂等）。
 *
 * 背景：设置页 Base URL 由用户手输/粘贴，输入法或粘贴常把 scheme 残渣追加到 URL 末尾
 * （如 `https://note3-prev-api.askdiandian.com/v1/chat/completionshttps`），导致请求命中
 * 错误路径 404。本函数：
 * 1. trim 首尾空白、去尾斜杠；
 * 2. 反复剥离尾部的游离 scheme 记号（`https`/`http`/`https:`/`https://`…，大小写不敏感）；
 * 3. 缺 scheme 时补 `https://`（裸主机 / `host:port`）。
 *
 * 空白输入原样返回；整串剥完只剩 scheme 视为无效返回空串。
 * 注：URL 若合法以 `http(s)`/`http:`/`http://` 结尾（如路径段恰好叫 http），会丢失该后缀——
 * 真实 LLM base URL 不会这样，属可接受取舍。
 */
fun normalizeBaseUrl(raw: String): String {
    var url = raw.trim()
    if (url.isEmpty()) return url

    while (true) {
        val t = url.trim().trimEnd('/')
        // 用 dropLast 而非 removeSuffix：removeSuffix 大小写敏感，会漏掉 "…/completionsHTTPS" 这类大写残渣。
        val stripped = when {
            // 整串就是 scheme：不再剥（后面统一判无效）
            t.equals("https", ignoreCase = true) || t.equals("http", ignoreCase = true) -> t
            t.endsWith("https://", ignoreCase = true) -> t.dropLast("https://".length)
            t.endsWith("http://", ignoreCase = true) -> t.dropLast("http://".length)
            t.endsWith("https:", ignoreCase = true) -> t.dropLast("https:".length)
            t.endsWith("http:", ignoreCase = true) -> t.dropLast("http:".length)
            t.endsWith("https", ignoreCase = true) -> t.dropLast("https".length)
            t.endsWith("http", ignoreCase = true) -> t.dropLast("http".length)
            else -> null
        }
        if (stripped == null || stripped == t) break
        url = stripped
    }

    url = url.trim().trimEnd('/')
    if (url.isEmpty()) return ""
    if (url.equals("http", ignoreCase = true) || url.equals("https", ignoreCase = true)) return ""
    if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
        url = "https://$url"
    }
    return url
}
