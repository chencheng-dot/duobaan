package org.example.duobaan.util;

import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/**
 * 公共 URL / 文本清洗器（专门为用户「直接复制 Markdown 行」这种脏数据场景设计）。
 *
 * <h3>典型历史脏数据（用户截图实锤）</h3>
 * <pre>
 *   > `https://dashscope.aliyuncs.com/api/v1/services/aigc/.../video-synthesis`platform.q...
 *   ^^^ 前缀带 ">" + 反引号 + 中文 `` ` ``，Java URI 会直接抛 Illegal character in scheme name at index 0
 * </pre>
 *
 * <h3>清洗规则（顺序敏感，不要乱调）</h3>
 * <ol>
 *   <li>去首尾空白、\r \n \t \u00A0</li>
 *   <li>去 Markdown/mistal URL 常见包络：首尾的 ` >、< >、` "、'、{ }、[ ( 、] )</li>
 *   <li>截断尾部被 Markdown 误拼接的多余片段（如 ```` ` ```xxx、````platform.qq…````、中文全角括号、中文省略号）</li>
 *   <li>去掉 scheme 前的奇怪字符（保证第一个合法 scheme 前面没有东西）</li>
 *   <li>对常见 endpoint-action 误拼进 baseUrl 的做还原：末尾若出现
 *        /videos/generations、/images/generations、/audio/speech、/audio/transcriptions、
 *        /completions、/chat/completions、/services/.../video-synthesis、
 *        /services/.../image-synthesis 等具体路径，自动截掉，只保留根 base</li>
 *   <li>末尾多余的斜杠统一处理成 1 个（调用方用 ensureTrailing 再统一拼）</li>
 * </ol>
 */
public final class UrlSanitizer {

    private UrlSanitizer() {}

    /** 具体 action 路径：如果用户把 endpoint 当作 baseUrl 粘进来，需要从尾部砍掉 */
    private static final Pattern ACTION_SUFFIX = Pattern.compile(
            "(?i)/("
            // OpenAI 兼容：4 模态 + 文本 chat/completions
            + "videos/generations|images/generations|audio/speech|audio/transcriptions|chat/completions|completions|embeddings|models"
            // DashScope 原生（用户常见）
            + "|services/aigc/video-generation/video-synthesis[^/]*"
            + "|services/aigc/multimodal-generation/generation[^/]*"
            + "|services/aigc/image-generation/generation[^/]*"
            // 常见结尾：反引号/引号被截断后，留下的尾随字符
            + ")$");

    /** 首尾「包络字符」：Markdown、引用、代码反引号、括号、引号、引用块 > */
    private static final String ENVELOPE_CHARS = " \t\n\r\u00A0`'\"<>()[]{}>|_*：:·•";

    /**
     * 入口：对 baseUrl / location 等可能被用户粘成 Markdown 行的文本做强力清洗。
     * 返回 null 仅当输入为 null；否则始终返回字符串（可能为空串）。
     */
    public static String sanitizeBaseUrl(String raw) {
        if (raw == null) return null;
        String s = raw;
        // 1. 去两端常规空白 + 控制字符
        s = s.strip();
        if (s.isEmpty()) return s;

        // 2. 循环去除首尾 Markdown 包络字符（可能有多层）
        boolean changed;
        do {
            changed = false;
            while (!s.isEmpty() && ENVELOPE_CHARS.indexOf(s.charAt(0)) >= 0) {
                s = s.substring(1);
                changed = true;
            }
            while (!s.isEmpty() && ENVELOPE_CHARS.indexOf(s.charAt(s.length() - 1)) >= 0) {
                s = s.substring(0, s.length() - 1);
                changed = true;
            }
        } while (changed && !s.isEmpty());
        if (s.isEmpty()) return s;

        // 3. 尾部若有拼接错误（常见是 Markdown 行尾粘了平台名），粗暴砍到 scheme://host/path「看起来还算像 URL」为止：
        //    如果尾部出现第一个非 URL 字符（中文/`` ` ``/平台域名如 platform.qq.com、dashscope... 这种重复多余段），且前面有空格，截断。
        //    更稳健：只保留从 "http"（或 https）开头直到下一个空白、反引号、或中文全角字符之前的内容。
        int idxHttp = indexOfHttp(s);
        if (idxHttp > 0) s = s.substring(idxHttp);

        // 进一步：从第一个字符开始直到「空白/反引号/全角字符/ASCII 大于 126」的字符为止，作为 URL 真身。
        StringBuilder sb = new StringBuilder();
        boolean inQueryOrHash = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c <= 0x20 || c == '`' || c == '"' || c == '\'' || c == '<' || c == '>' || c > 126) {
                // 注意 & 和 # 以及 = ? 允许（查询串）；中文(>126) 直接砍断
                if (c == '#' || c == '?' || c == '&' || c == '=') {
                    if (c == '?' || c == '#') inQueryOrHash = true;
                    sb.append(c);
                    continue;
                }
                // 其它非 ASCII：URL 编码不处理，直接截断 —— 我们的 baseUrl 场景不该含中文
                break;
            }
            if (c == '?' || c == '#') inQueryOrHash = true;
            sb.append(c);
        }
        s = sb.toString();
        if (s.isEmpty()) return s;

        // 4. 去重复尾部 action 路径（用户把 endpoint 直接塞进 baseUrl 是最常见的 404 根因）
        //    最多循环 3 次：因为偶尔有「/compatible-mode/v1/videos/generations」这种嵌套后缀
        for (int i = 0; i < 3; i++) {
            java.util.regex.Matcher m = ACTION_SUFFIX.matcher(s);
            // 只匹配末尾段；注意 lastIndexOf 可能返回 -1（如天气 Host 无 /），find 起点 ≥ 0
            int startFrom = Math.max(0, s.lastIndexOf('/'));
            if (m.find(startFrom) && m.end() == s.length()) {
                s = s.substring(0, m.start());
            } else {
                break;
            }
        }

        // 去掉尾部多余 / 但保留 scheme://（http:// 不能破坏）
        while (s.length() > 1 && s.endsWith("/")) {
            int slashCount = 0;
            int j = s.length() - 1;
            while (j >= 0 && s.charAt(j) == '/') { j--; slashCount++; }
            if (slashCount <= 1) break;
            // 如果 "://" 后只剩 /（http:///foo），这里不破坏它，只处理尾部 /
            if (s.contains("://")) {
                int schemeSep = s.indexOf("://");
                if (j <= schemeSep + 2) break;
            }
            s = s.substring(0, s.length() - 1);
        }

        // 5. 再去一次空白/包络（防止上面操作后出现）
        s = s.strip();
        return s;
    }

    private static int indexOfHttp(String s) {
        int a = s.indexOf("http://");
        int b = s.indexOf("https://");
        if (a < 0) return b;
        if (b < 0) return a;
        return Math.min(a, b);
    }

    /** 通用 trimToNull：null → null */
    public static String cleanText(String s) {
        if (!StringUtils.hasText(s)) return s == null ? null : null;
        return s.trim();
    }
}
