package org.example.duobaan.util;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.InetAddress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link OutboundUrlGuard#verify(String)} 单元测试（SSRF 出网守卫）。
 *
 * <p>分两类：不需要 DNS 的用例（始终启用）；公网域名放行用例依赖 DNS，只有在域名能解析成
 * 纯公网地址时才断言，否则用 {@code assumeTrue} 跳过，避免无网环境误报失败。
 */
@DisplayName("OutboundUrlGuard.verify —— 出网地址守卫")
class OutboundUrlGuardTest {

    /** 以下地址无需 DNS 解析即可判定，任何环境都必须始终执行。 */
    @ParameterizedTest(name = "必须拒绝: [{0}]")
    @ValueSource(strings = {
            "http://127.0.0.1:8080/x",                  // IPv4 环回
            "http://localhost/x",                       // 主机名黑名单
            "http://169.254.169.254/latest/meta-data/", // 云元数据 / 链路本地
            "http://10.0.0.1/",                         // RFC1918
            "http://192.168.1.1/",                      // RFC1918
            "http://[::1]/x",                           // IPv6 环回
            "file:///etc/passwd",                       // scheme 不在白名单
            "ftp://example.com/",                       // scheme 不在白名单
            "",                                         // 空串
            "not a url"                                 // 非法 URI
    })
    void blockedAddressesThrowIllegalArgumentException(String url) {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> OutboundUrlGuard.verify(url), "应拒绝: [" + url + "]");
        assertNotNull(ex.getMessage(), "拒绝时必须给出可排查的原因: [" + url + "]");
        assertTrue(!ex.getMessage().isBlank(), "拒绝原因不能是空白: [" + url + "]");
    }

    @Test
    @DisplayName("null 与纯空白同样抛出 IllegalArgumentException")
    void nullAndBlankAreRejected() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> OutboundUrlGuard.verify(null)),
                () -> assertThrows(IllegalArgumentException.class, () -> OutboundUrlGuard.verify("   ")),
                () -> assertThrows(IllegalArgumentException.class, () -> OutboundUrlGuard.verify("\t\n"))
        );
    }

    /** 拒绝原因里必须带上出问题的主机/scheme，否则线上无法定位是哪条配置配错了。 */
    @Test
    @DisplayName("拒绝原因指向具体主机名或 scheme")
    void rejectionMessageNamesOffendingHostOrScheme() {
        assertAll(
                () -> assertTrue(rejectionMessage("http://127.0.0.1:8080/x").contains("127.0.0.1")),
                () -> assertTrue(rejectionMessage("http://localhost/x").contains("localhost")),
                () -> assertTrue(rejectionMessage("http://169.254.169.254/latest/meta-data/").contains("169.254.169.254")),
                () -> assertTrue(rejectionMessage("http://10.0.0.1/").contains("10.0.0.1")),
                () -> assertTrue(rejectionMessage("http://192.168.1.1/").contains("192.168.1.1")),
                () -> assertTrue(rejectionMessage("http://[::1]/x").contains("[::1]")),
                () -> assertTrue(rejectionMessage("file:///etc/passwd").contains("file")),
                () -> assertTrue(rejectionMessage("ftp://example.com/").contains("ftp"))
        );
    }

    /** IP 字面量走 InetAddress 字面量解析，不触发 DNS 查询，因此本用例始终启用。 */
    @Test
    @DisplayName("公网 IP 字面量放行（无需 DNS）")
    void publicIpLiteralsAreAllowed() {
        assertAll(
                () -> assertDoesNotThrow(() -> OutboundUrlGuard.verify("https://1.1.1.1/v1/chat/completions")),
                () -> assertDoesNotThrow(() -> OutboundUrlGuard.verify("http://8.8.8.8/")),
                () -> assertDoesNotThrow(() -> OutboundUrlGuard.verify("https://223.5.5.5/"))
        );
    }

    @Test
    @DisplayName("OpenAI 公网域名放行（仅当 DNS 解析出纯公网地址时断言）")
    void publicOpenAiDomainIsAllowedWhenDnsResolvesToPublicAddress() {
        assumeTrue(resolvesToPublicOnly("api.openai.com"),
                "需要 DNS 解析（且解析结果必须是公网地址）：api.openai.com");
        assertDoesNotThrow(() -> OutboundUrlGuard.verify("https://api.openai.com/v1/chat/completions"));
    }

    @Test
    @DisplayName("DashScope 公网域名放行（仅当 DNS 解析出纯公网地址时断言）")
    void publicDashScopeDomainIsAllowedWhenDnsResolvesToPublicAddress() {
        assumeTrue(resolvesToPublicOnly("dashscope.aliyuncs.com"),
                "需要 DNS 解析（且解析结果必须是公网地址）：dashscope.aliyuncs.com");
        assertDoesNotThrow(() -> OutboundUrlGuard.verify("https://dashscope.aliyuncs.com/api/v1/tasks/x"));
    }

    private static String rejectionMessage(String url) {
        return String.valueOf(assertThrows(IllegalArgumentException.class,
                () -> OutboundUrlGuard.verify(url), "应拒绝: " + url).getMessage());
    }

    /** DNS 可解析、且所有 A/AAAA 记录都不是环回/链路本地/站点本地/组播地址时返回 true。 */
    private static boolean resolvesToPublicOnly(String host) {
        try {
            InetAddress[] addrs = InetAddress.getAllByName(host);
            if (addrs.length == 0) {
                return false;
            }
            for (InetAddress a : addrs) {
                if (a.isLoopbackAddress() || a.isAnyLocalAddress() || a.isLinkLocalAddress()
                        || a.isSiteLocalAddress() || a.isMulticastAddress()) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
