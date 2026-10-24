package com.google.refine.auth;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

import java.util.Locale;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;

import com.jiongche.sdk.sso.SessionContract;
import org.testng.annotations.Test;

/**
 * OR 直连登录闸门的单元测试：三态开关、凭据提取、导航/接口分流、消息资源。
 *
 * <p>不连真实平台——经 {@link RefineAuthGate.TokenProbe} 注入假的登录态探测。
 */
public class RefineAuthGateTest {

    /** 构造一个固定返回指定探测结果的闸门。 */
    private static RefineAuthGate gate(RefineAuthGate.Mode mode, RefineAuthGate.Probe result) {
        return new RefineAuthGate(mode, "http://127.0.0.1:8090", 0, token -> result);
    }

    /** 构造一个带请求头的请求 mock。 */
    private static HttpServletRequest request(String headerName, String headerValue, String accept) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        if (headerName != null) {
            when(request.getHeader(headerName)).thenReturn(headerValue);
        }
        if (accept != null) {
            when(request.getHeader("Accept")).thenReturn(accept);
        }
        when(request.getRequestURI()).thenReturn("/");
        return request;
    }

    @Test
    public void testOffModeAllowsEverything() {
        // off 模式零影响、可回退：无凭据也放行
        assertEquals(gate(RefineAuthGate.Mode.OFF, RefineAuthGate.Probe.NOT_LOGGED_IN)
                .decide(request(null, null, "text/html")),
                RefineAuthGate.Decision.ALLOW);
        assertEquals(gate(RefineAuthGate.Mode.OFF, RefineAuthGate.Probe.UNAVAILABLE)
                .decide(request(null, null, "application/json")),
                RefineAuthGate.Decision.ALLOW);
    }

    @Test
    public void testLogModeObservesOnly() {
        // log 模式只记日志放行
        assertEquals(gate(RefineAuthGate.Mode.LOG, RefineAuthGate.Probe.NOT_LOGGED_IN)
                .decide(request(null, null, "text/html")),
                RefineAuthGate.Decision.ALLOW);
        assertEquals(gate(RefineAuthGate.Mode.LOG, RefineAuthGate.Probe.UNAVAILABLE)
                .decide(request(null, null, "text/html")),
                RefineAuthGate.Decision.ALLOW);
    }

    @Test
    public void testNoCredentialIsRejected() {
        // 无任何凭据 → 未登录
        assertEquals(gate(RefineAuthGate.Mode.BLOCK, RefineAuthGate.Probe.NOT_LOGGED_IN)
                .decide(request(null, null, "text/html")),
                RefineAuthGate.Decision.REDIRECT);
    }

    @Test
    public void testLoggedInIsAllowed() {
        assertEquals(gate(RefineAuthGate.Mode.BLOCK, RefineAuthGate.Probe.LOGGED_IN)
                .decide(request(SessionContract.TOKEN_HEADER, "tok", "text/html")),
                RefineAuthGate.Decision.ALLOW);
    }

    @Test
    public void testNavigationRedirectsAndApiReturnsUnauthorized() {
        // 浏览器导航 → 跳平台登录；接口类请求 → 401
        assertEquals(gate(RefineAuthGate.Mode.BLOCK, RefineAuthGate.Probe.NOT_LOGGED_IN)
                .decide(request(null, null, "text/html,application/xhtml+xml")),
                RefineAuthGate.Decision.REDIRECT);
        assertEquals(gate(RefineAuthGate.Mode.BLOCK, RefineAuthGate.Probe.NOT_LOGGED_IN)
                .decide(request(null, null, "application/json")),
                RefineAuthGate.Decision.UNAUTHORIZED);
        assertEquals(gate(RefineAuthGate.Mode.BLOCK, RefineAuthGate.Probe.NOT_LOGGED_IN)
                .decide(request(null, null, "*/*")),
                RefineAuthGate.Decision.UNAUTHORIZED);
    }

    @Test
    public void testPlatformUnavailableFailsClosed() {
        // 带着凭据但平台不可达 → 失败关闭（503），不静默放行
        assertEquals(gate(RefineAuthGate.Mode.BLOCK, RefineAuthGate.Probe.UNAVAILABLE)
                .decide(request(SessionContract.TOKEN_HEADER, "tok", "text/html")),
                RefineAuthGate.Decision.UNAVAILABLE);
        assertEquals(gate(RefineAuthGate.Mode.BLOCK, RefineAuthGate.Probe.UNAVAILABLE)
                .decide(request(SessionContract.TOKEN_HEADER, "tok", "application/json")),
                RefineAuthGate.Decision.UNAVAILABLE);
    }

    @Test
    public void testTokenExtractionPrefersHeaderThenCookie() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(SessionContract.TOKEN_HEADER)).thenReturn("header-token");
        when(request.getCookies()).thenReturn(new Cookie[] { new Cookie(SessionContract.TOKEN_COOKIE, "cookie-token") });
        assertEquals(RefineAuthGate.tokenOf(request), "header-token");

        HttpServletRequest cookieOnly = mock(HttpServletRequest.class);
        when(cookieOnly.getCookies()).thenReturn(new Cookie[] { new Cookie(SessionContract.TOKEN_COOKIE, "cookie-token") });
        assertEquals(RefineAuthGate.tokenOf(cookieOnly), "cookie-token");

        assertEquals(RefineAuthGate.tokenOf(mock(HttpServletRequest.class)), null);
    }

    @Test
    public void testModeParsingFailsClosed() {
        assertEquals(RefineAuthGate.parseMode("off"), RefineAuthGate.Mode.OFF);
        assertEquals(RefineAuthGate.parseMode(" LOG "), RefineAuthGate.Mode.LOG);
        assertEquals(RefineAuthGate.parseMode("block"), RefineAuthGate.Mode.BLOCK);
        // 未知取值 / 空值保守按 block（失败关闭）
        assertEquals(RefineAuthGate.parseMode(null), RefineAuthGate.Mode.BLOCK);
        assertEquals(RefineAuthGate.parseMode("bogus"), RefineAuthGate.Mode.BLOCK);
    }

    @Test
    public void testMessageBundle() {
        String zh = RefineAuthGate.message("auth.loginRequired", Locale.SIMPLIFIED_CHINESE);
        assertNotNull(zh);
        assertTrue(zh.contains("登录"), "中文消息应读取 RefineAuthMessages_zh：" + zh);

        String en = RefineAuthGate.message("auth.loginRequired", Locale.ENGLISH);
        assertNotNull(en);
        assertTrue(en.length() > 0, "英文消息不应为空");
    }
}
