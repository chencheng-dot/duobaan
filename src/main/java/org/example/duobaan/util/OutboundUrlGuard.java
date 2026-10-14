package org.example.duobaan.util;

import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** 出网地址守卫：只允许 http/https，禁止私网/环回/链路本地/元数据地址与可疑主机名。
 *  用于所有由用户配置（baseUrl）或厂商响应（audioUrl 等）驱动的服务端出网请求。 */
public final class OutboundUrlGuard {

    private OutboundUrlGuard() {}

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");
    private static final Pattern BLOCKED_HOST = Pattern.compile(
            "(?i)^(localhost|.*\\.local|.*\\.internal|metadata\\..*|.*\\.metadata)$");

    public static void verify(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) throw new IllegalArgumentException("出网地址为空");
        URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("出网地址非法：" + rawUrl);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!ALLOWED_SCHEMES.contains(scheme)) throw new IllegalArgumentException("仅允许 http/https，收到：" + scheme);
        String host = uri.getHost();
        if (host == null || host.isBlank()) throw new IllegalArgumentException("出网地址缺少主机名：" + rawUrl);
        if (BLOCKED_HOST.matcher(host).matches()) throw new IllegalArgumentException("禁止访问的主机：" + host);
        if (isIpLiteral(host)) {
            try {
                assertPublic(InetAddress.getByName(host), host);
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalArgumentException("非法 IP：" + host);
            }
        } else {
            try {
                for (InetAddress addr : InetAddress.getAllByName(host)) assertPublic(addr, host);
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalArgumentException("无法解析主机：" + host);
            }
        }
    }

    private static void assertPublic(InetAddress addr, String host) {
        if (addr.isLoopbackAddress() || addr.isAnyLocalAddress() || addr.isLinkLocalAddress()
                || addr.isSiteLocalAddress() || addr.isMulticastAddress()) {
            // 不把解析结果（内网 IP）带进异常文案：该文案会冒泡到前端 / 落库，
            // 泄露解析结果等于给人一个「探测某主机名是否指向内网」的探针。
            throw new IllegalArgumentException("禁止访问内网/环回地址：" + host);
        }
        byte[] ip = addr.getAddress();
        if (ip.length == 4 && (ip[0] & 0xFF) == 169 && (ip[1] & 0xFF) == 254) {   // 169.254.0.0/16 云元数据
            throw new IllegalArgumentException("禁止访问链路本地/元数据地址：" + host);
        }
    }

    private static boolean isIpLiteral(String host) {
        return host.indexOf(':') >= 0 || host.matches("\\d{1,3}(\\.\\d{1,3}){3}");
    }
}
