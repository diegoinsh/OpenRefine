package com.google.refine.auth;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.concurrent.ConcurrentHashMap;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jiongche.sdk.sso.SessionContract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OR 直连登录闸门：访问者必须先在能力平台（RCP）登录，才允许使用 OR。
 *
 * <p>判定方式（不共享任何密钥）：把浏览器带来的会话凭据原样回传给平台的
 * {@code GET /api/auth/me}（该端点免会话，但会校验调用方给出的凭据），
 * 凭据有效即返回 {@code loggedIn=true}。
 *
 * <p>凭据来源与平台一致（{@link SessionContract}）：请求头 {@code X-Auth-Token} 优先，
 * 其次 Cookie {@code ruyi_token}。平台登录时下发的 Cookie 路径为 {@code /}，
 * 而 Cookie 不区分端口，故同主机下浏览器访问 OR（3333）时会自动携带该凭据。
 *
 * <p>三态开关（JVM 系统属性 {@code refine.auth.enforce}）：
 *
 * <ul>
 *   <li>{@code off}：完全关闭（零影响、可回退）；</li>
 *   <li>{@code log}：只记日志放行——用于上线前观察真实访问面；</li>
 *   <li>{@code block}（默认）：未登录一律拒绝。</li>
 * </ul>
 *
 * <p>相关系统属性：{@code refine.auth.platform-url}（平台地址，缺省
 * {@code http://127.0.0.1:8090}）、{@code refine.auth.cache-seconds}（登录态缓存秒数，缺省 5）。
 *
 * <p>拒绝响应分两种形态：浏览器导航（Accept 含 {@code text/html}）返回 302 跳平台登录页
 * 并带 {@code redirect} 回跳参数；接口类请求返回 401 JSON。
 */
public class RefineAuthGate {

    /** 闸门模式。 */
    public enum Mode {
        OFF, LOG, BLOCK
    }

    /** 单次请求的判定结果。 */
    public enum Decision {
        /** 放行。 */
        ALLOW,
        /** 未登录且是浏览器导航：跳转平台登录页。 */
        REDIRECT,
        /** 未登录且是接口类请求：返回 401。 */
        UNAUTHORIZED,
        /** 平台不可达：返回 503。 */
        UNAVAILABLE
    }

    /** 登录态探测结果。 */
    public enum Probe {
        LOGGED_IN, NOT_LOGGED_IN, UNAVAILABLE
    }

    /** 闸门模式开关。 */
    public static final String PROP_ENFORCE = "refine.auth.enforce";
    /** 平台地址。 */
    public static final String PROP_PLATFORM_URL = "refine.auth.platform-url";
    /** 登录态缓存秒数。 */
    public static final String PROP_CACHE_SECONDS = "refine.auth.cache-seconds";

    private static final Logger logger = LoggerFactory.getLogger(RefineAuthGate.class);
    private static final String BUNDLE_NAME = "com.google.refine.auth.RefineAuthMessages";
    private static final int MAX_CACHE_ENTRIES = 1024;
    private static final String JSON_CONTENT_TYPE = "application/json";

    private final Mode mode;
    private final String platformUrl;
    private final int platformPort;
    private final int cacheMillis;
    private final TokenProbe probe;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    /** 登录态探测函数（令牌 → 探测结果），默认实现走平台 {@code /api/auth/me}。 */
    public interface TokenProbe {
        Probe probe(String token);
    }

    private static final class Cached {
        final Probe result;
        final long expiresAt;

        Cached(Probe result, long expiresAt) {
            this.result = result;
            this.expiresAt = expiresAt;
        }
    }

    public RefineAuthGate(Mode mode, String platformUrl, int cacheSeconds, TokenProbe probe) {
        this.mode = mode == null ? Mode.OFF : mode;
        this.platformUrl = stripTrailingSlash(platformUrl == null || platformUrl.isBlank()
                ? "http://127.0.0.1:8090"
                : platformUrl.trim());
        this.platformPort = parsePort(this.platformUrl);
        this.cacheMillis = Math.max(0, cacheSeconds) * 1000;
        this.probe = probe == null ? this::httpProbe : probe;
    }

    /** 从 JVM 系统属性装配（{@code -Drefine.auth.*}，如写在 refine.ini 的 JAVA_OPTIONS 中）。 */
    public static RefineAuthGate fromConfiguration() {
        String enforce = System.getProperty(PROP_ENFORCE, "block");
        String url = System.getProperty(PROP_PLATFORM_URL, "http://127.0.0.1:8090");
        int cache = 5;
        try {
            cache = Integer.parseInt(System.getProperty(PROP_CACHE_SECONDS, "5"));
        } catch (NumberFormatException e) {
            logger.warn("{} 取值非法，按 5 秒处理：{}", PROP_CACHE_SECONDS, e.getMessage());
        }
        return new RefineAuthGate(parseMode(enforce), url, cache, null);
    }

    /** 解析三态开关；无法识别时按 {@code block}（失败关闭）。 */
    public static Mode parseMode(String raw) {
        if (raw == null) {
            return Mode.BLOCK;
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if ("off".equals(v)) {
            return Mode.OFF;
        }
        if ("log".equals(v)) {
            return Mode.LOG;
        }
        return Mode.BLOCK;
    }

    public Mode mode() {
        return mode;
    }

    /** 启动日志用的一句话说明。 */
    public String describe() {
        if (mode == Mode.OFF) {
            return "登录闸门：关闭（" + PROP_ENFORCE + "=off）";
        }
        return String.format("登录闸门：%s（平台=%s，缓存=%ds）", mode.name().toLowerCase(Locale.ROOT),
                platformUrl, cacheMillis / 1000);
    }

    /**
     * 判定一次请求是否放行。
     *
     * @param request 当前请求
     * @return 判定结果
     */
    public Decision decide(HttpServletRequest request) {
        if (mode == Mode.OFF) {
            return Decision.ALLOW;
        }
        String token = tokenOf(request);
        Probe result = token == null ? Probe.NOT_LOGGED_IN : cachedProbe(token);
        if (result == Probe.LOGGED_IN) {
            return Decision.ALLOW;
        }
        if (mode == Mode.LOG) {
            logger.info("[登录] 观察模式放行 {}（{}）", request.getRequestURI(), result);
            return Decision.ALLOW;
        }
        if (result == Probe.UNAVAILABLE) {
            logger.warn("[登录] 平台不可达，拒绝 {}：{}", request.getRequestURI(), platformUrl);
            return Decision.UNAVAILABLE;
        }
        logger.info("[登录] 拒绝未登录访问：{}", request.getRequestURI());
        return isNavigation(request) ? Decision.REDIRECT : Decision.UNAUTHORIZED;
    }

    /** 写出拒绝响应（302 跳登录 / 401 JSON / 503 JSON）。 */
    public void respondDenied(HttpServletRequest request, HttpServletResponse response, Decision decision)
            throws IOException {
        Locale locale = request.getLocale();
        if (decision == Decision.REDIRECT) {
            String location = loginUrl(request);
            response.setStatus(HttpServletResponse.SC_FOUND);
            response.setHeader("Location", location);
            response.setContentType("text/html;charset=UTF-8");
            PrintWriter writer = response.getWriter();
            writer.write("<!doctype html><meta charset=\"utf-8\"><title>请先登录</title>"
                    + "<p>" + escapeHtml(message("auth.loginRequired", locale)) + "</p>");
            writer.flush();
            return;
        }
        boolean unavailable = decision == Decision.UNAVAILABLE;
        response.setStatus(unavailable ? HttpServletResponse.SC_SERVICE_UNAVAILABLE
                : HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding("UTF-8");
        response.setContentType(JSON_CONTENT_TYPE);
        PrintWriter writer = response.getWriter();
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(Map.of(
                "code", "error",
                "message", message(unavailable ? "auth.platformUnavailable" : "auth.loginRequired", locale)));
        writer.write(json);
        writer.flush();
    }

    /** 平台登录页地址（带回跳参数）。 */
    public String loginUrl(HttpServletRequest request) {
        String host = requestHost(request);
        String base = "http://" + host + ":" + platformPort;
        String target = originalUrl(request);
        return base + SessionContract.LOGIN_PATH + "?redirect="
                + URLEncoder.encode(target, StandardCharsets.UTF_8);
    }

    /** 提取请求携带的会话凭据（请求头优先，其次 Cookie）。 */
    public static String tokenOf(HttpServletRequest request) {
        String header = request.getHeader(SessionContract.TOKEN_HEADER);
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if (SessionContract.TOKEN_COOKIE.equals(c.getName()) && c.getValue() != null
                        && !c.getValue().isBlank()) {
                    return c.getValue().trim();
                }
            }
        }
        return null;
    }

    /** 按客户端语言取提示（缺失时回退键名）。 */
    static String message(String key, Locale locale) {
        try {
            Locale effective = locale != null ? locale : Locale.ROOT;
            ResourceBundle bundle = ResourceBundle.getBundle(BUNDLE_NAME, effective,
                    ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
            return MessageFormat.format(bundle.getString(key), new Object[0]);
        } catch (Exception e) {
            logger.warn("登录提示资源不可用，回退为键名: {}", key);
            return key;
        }
    }

    // ---- 内部 ----

    private Probe cachedProbe(String token) {
        Cached hit = cache.get(token);
        long now = System.currentTimeMillis();
        if (hit != null && hit.expiresAt > now) {
            return hit.result;
        }
        Probe result;
        try {
            result = probe.probe(token);
        } catch (RuntimeException e) {
            logger.warn("登录态探测异常，按平台不可达处理: {}", e.toString());
            result = Probe.UNAVAILABLE;
        }
        if (cache.size() > MAX_CACHE_ENTRIES) {
            cache.entrySet().removeIf(e -> e.getValue().expiresAt <= now);
        }
        cache.put(token, new Cached(result, now + cacheMillis));
        return result;
    }

    private Probe httpProbe(String token) {
        try {
            HttpClient client = client();
            HttpRequest req = HttpRequest.newBuilder(URI.create(platformUrl + SessionContract.ME_PATH))
                    .header(SessionContract.TOKEN_HEADER, token)
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                logger.warn("平台 {} 返回 {}", platformUrl + SessionContract.ME_PATH, resp.statusCode());
                return Probe.NOT_LOGGED_IN;
            }
            JsonNode root = new ObjectMapper().readTree(resp.body());
            JsonNode data = root.path("data");
            return data.path("loggedIn").asBoolean(false) ? Probe.LOGGED_IN : Probe.NOT_LOGGED_IN;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Probe.UNAVAILABLE;
        } catch (Exception e) {
            logger.warn("平台不可达（{}）：{}", platformUrl, e.toString());
            return Probe.UNAVAILABLE;
        }
    }

    private volatile HttpClient httpClient;

    private HttpClient client() {
        HttpClient c = httpClient;
        if (c == null) {
            synchronized (this) {
                c = httpClient;
                if (c == null) {
                    c = HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(3))
                            .followRedirects(HttpClient.Redirect.NEVER)
                            .build();
                    httpClient = c;
                }
            }
        }
        return c;
    }

    /** 是否浏览器导航（而非接口类请求）。 */
    private static boolean isNavigation(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        if (accept == null || accept.isBlank()) {
            // 无 Accept 头（命令行/健康探测等）：按导航处理，给出可点击的跳转提示
            return true;
        }
        if (accept.contains("text/html") || accept.contains("application/xhtml")) {
            return true;
        }
        // 接口类请求（application/json、*/* 等）：一律 401 JSON，不做跳转
        return false;
    }

    private static String originalUrl(HttpServletRequest request) {
        StringBuffer url = request.getRequestURL();
        String query = request.getQueryString();
        if (query != null && !query.isBlank()) {
            url.append('?').append(query);
        }
        return url.toString();
    }

    /** 取浏览器访问 OR 时使用的主机名（保证跳转后的 Cookie 域与当前一致）。 */
    private static String requestHost(HttpServletRequest request) {
        String host = request.getServerName();
        if (host == null || host.isBlank()) {
            host = "127.0.0.1";
        }
        return host;
    }

    private static int parsePort(String url) {
        try {
            int p = URI.create(url).getPort();
            return p > 0 ? p : 8090;
        } catch (Exception e) {
            return 8090;
        }
    }

    private static String stripTrailingSlash(String url) {
        String v = url;
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }

    private static String escapeHtml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
