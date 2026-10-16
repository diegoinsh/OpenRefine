/*

Copyright 2010, Google Inc.
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

    * Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.
    * Redistributions in binary form must reproduce the above
copyright notice, this list of conditions and the following disclaimer
in the documentation and/or other materials provided with the
distribution.
    * Neither the name of Google Inc. nor the names of its
contributors may be used to endorse or promote products derived from
this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

*/

package com.google.refine;

import java.io.File;
import java.io.FileInputStream;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.net.URLDecoder;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;

import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.config.Configurator;
import org.noear.solon.Solon;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Handler;
import org.noear.solon.server.tomcat.TomcatServerBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * POC 引导入口：用 Solon(boot) + Tomcat(transport) 承载既有的 {@link RefineServlet}（Butterfly）。
 *
 * <p>目标：验证「容器层替换」的可行性与成本——121 个命令类、前端 {@code /command/...} 契约、Butterfly 模块装载
 * 全部零改动，只把外层 HTTP 容器从 Jetty 换成 Solon。
 *
 * <p>做法（薄采用）：Solon 只负责 server 装配与请求入口；把请求原样交给现成 servlet 处理。
 * 为此需要在三处做最小桥接：
 * <ol>
 * <li>Solon 适配器把容器 servlet 映射在 {@code /}（default 语义），其 {@code getPathInfo()} 为 null，
 * 而 Butterfly/RefineServlet 依赖 {@code /*} 语义（pathInfo = 完整路径）→ 用 {@link PathInfoRequest} 包装。</li>
 * <li>Solon 不配置 webapp docroot，{@code ServletContext.getRealPath("/")} 为空，而 Butterfly 强依赖它定位
 * {@code WEB-INF/butterfly.properties} 与模块目录 → 用 {@link RefineServletContext} 兜底。</li>
 * <li>RefineServlet 的 5 个 init-param（原由 Jetty {@code RefineServer.configure} 注入）→ {@link RefineServletConfig}。</li>
 * </ol>
 *
 * <p>启动后还会做一轮「容器语义对齐」（{@link #alignContainerSemantics()}），把原 Jetty 装配里
 * 但 Solon/Tomcat 默认不具备的语义补齐：部署期预热（原 {@code setInitOrder(1)}）、gzip
 * （原 {@code jetty-web.xml} 的 GzipHandler）、form 体积上限（原 {@code setMaxFormContentSize(64MB)}）、
 * 连接空闲超时与并发上限（原 {@code RefineServer} 自设连接器与线程池）。
 */
public class RefineSolon {

    static private final String JAVAX_SERVLET_CONTEXT_TEMPDIR = "javax.servlet.context.tempdir";

    /** 对齐 {@code jetty-web.xml} GzipHandler 的 minGzipSize。 */
    static private final long GZIP_MIN_SIZE = 2048;

    /** 对齐 {@code jetty-web.xml} GzipHandler 的 includedMimeTypesList。 */
    static private final String GZIP_MIME_TYPES = "text/html,text/plain,text/xml,application/xhtml+xml,text/css,"
            + "text/javascript,application/javascript,application/json,image/svg+xml";

    static final Logger logger = LoggerFactory.getLogger("refine_solon");

    /** Solon 装配出的 Tomcat 服务器实例（由 {@code app.onEvent} 在插件启动前捕获）。 */
    private static volatile TomcatServerBase solonTomcatServer;

    public static void main(String[] args) throws Exception {
        // 与 Refine.main() 对齐的 OR 自有启动属性（非容器相关，但影响行为）
        System.setProperty("refine.display.new.version.notice",
                Configurations.get("refine.display.new.version.notice", "true"));
        String logLevelArg = Configurations.get("refine.verbosity");
        if (logLevelArg != null && !logLevelArg.isEmpty()) {
            Configurator.setAllLevels(LogManager.getRootLogger().getName(), Level.toLevel(logLevelArg));
        }

        // 端口/主机沿用 OR 的配置键（refine.port / refine.interface），映射到 Solon 的 server.port / server.host
        String port = Configurations.get("refine.port", "3333");
        System.setProperty("server.port", port);
        String host = Configurations.get("refine.host", Configurations.get("refine.interface", "127.0.0.1"));
        if (!"0.0.0.0".equals(host)) {
            System.setProperty("server.host", host);
        }

        logger.info("Starting JinRefine on Solon (POC), port={}", port);

        Solon.start(RefineSolon.class, args, app -> {
            // 纯承载模式：关掉 Solon 自己的扫描/静态资源/会话，避免与 Butterfly 的资源装载重叠
            app.enableScanning(false);
            app.enableStaticfiles(false);
            app.enableSessionState(false);
            app.enableWebSocket(false);
            // 捕获 Solon 的 Tomcat 服务器实例（TomcatPlugin 启动时经 EventBus 发布），
            // 供 app 启动完成后对齐连接器语义使用
            app.onEvent(TomcatServerBase.class, server -> solonTomcatServer = server);
            // 把请求入口插在处理链最前面，命中后短路后续（含 Solon 路由）
            app.handler().prev(RefineSolonHandler.INSTANCE);
        });

        alignContainerSemantics();
    }

    /**
     * 启动后对齐原 Jetty 装配里的容器语义。逐项：
     * <ul>
     * <li><b>① 预热</b>：Jetty 由 {@code servlet.setInitOrder(1) + doStart()} 在部署期初始化 RefineServlet；
     * Solon 默认懒加载（首请求阻塞 ~20s）→ 此处显式预热，等价 load-on-startup。</li>
     * <li><b>② gzip</b>：原 {@code jetty-web.xml} 的 GzipHandler（minGzipSize=2048 + mime 列表）。
     * Tomcat 在 {@code AbstractHttp11Processor.prepareResponse()} 里逐响应读取协议压缩配置
     * （走 {@code CompressionConfig.useCompression}），故启动后设置同样生效。</li>
     * <li><b>③ SessionPath</b>：原 {@code web.xml} 的 {@code org.eclipse.jetty.servlet.SessionPath=/} 为
     * Jetty 专属；Tomcat 在根上下文（"\"）下会话 cookie path 本就为 "/"，且 OR 全库 0 处使用
     * {@code HttpSession} → 无需迁移（语义等价）。</li>
     * <li><b>④ 体积上限与并发/超时</b>：原 {@code setMaxFormContentSize(64MB)}、
     * 连接器 {@code idleTimeout=60s}、线程池 {@code maxThreads/queue} 自设值。
     * Tomcat 线程模型为 NIO（无 Jetty 那种有界工作队列语义，Solon 的 {@code setExecutor} 为 no-op），
     * 故以 {@code maxThreads + acceptCount} 近似 OR 的并发上限。</li>
     * </ul>
     */
    private static void alignContainerSemantics() {
        long started = System.currentTimeMillis();
        try {
            RefineSolonHandler.INSTANCE.warmup();
            logger.info("RefineServlet pre-initialized in {}ms (load-on-startup aligned)",
                    System.currentTimeMillis() - started);
        } catch (Throwable e) {
            logger.error("Failed to pre-initialize RefineServlet", e);
        }

        TomcatServerBase server = solonTomcatServer;
        if (server == null) {
            logger.warn("Solon Tomcat server was not captured; skip connector semantics alignment");
            return;
        }

        try {
            // Solon 未暴露 Tomcat 实例，暂以反射取 TomcatServerBase._server（POC 级桥接，Phase 3 自建装配时移除）
            Field field = TomcatServerBase.class.getDeclaredField("_server");
            field.setAccessible(true);
            Tomcat tomcat = (Tomcat) field.get(server);

            long maxFormContentSize = Configurations.getInteger("refine.max_form_content_size", 64 * 1048576); // 64MB
            int maxThreads = Configurations.getInteger("refine.queue.max_size", 300);
            int connectionTimeout = Configurations.getInteger("server.connection.max_idle_time", 60000);

            for (Connector connector : tomcat.getService().findConnectors()) {
                // ② gzip
                connector.setProperty("compression", "on");
                connector.setProperty("compressionMinSize", String.valueOf(GZIP_MIN_SIZE));
                connector.setProperty("compressibleMimeType", GZIP_MIME_TYPES);
                // ④ 体积上限 / 并发上限 / 空闲超时
                connector.setProperty("maxPostSize", String.valueOf(maxFormContentSize));
                connector.setProperty("maxSwallowSize", String.valueOf(maxFormContentSize));
                connector.setProperty("maxThreads", String.valueOf(maxThreads));
                connector.setProperty("acceptCount", String.valueOf(maxThreads));
                connector.setProperty("connectionTimeout", String.valueOf(connectionTimeout));
            }

            logger.info("Aligned connector semantics: gzip(min={}B, mime={} types), maxPostSize={}MB, "
                    + "maxThreads/acceptCount={}, connectionTimeout={}ms",
                    GZIP_MIN_SIZE, GZIP_MIME_TYPES.split(",").length, maxFormContentSize / 1048576,
                    maxThreads, connectionTimeout);
        } catch (Throwable e) {
            logger.error("Failed to align container connector semantics", e);
        }
    }

    /* -------------- 请求入口 -------------- */

    static final class RefineSolonHandler implements Handler {

        static final RefineSolonHandler INSTANCE = new RefineSolonHandler();

        private volatile RefineServlet servlet;

        private RefineServlet servlet() throws ServletException {
            RefineServlet s = servlet;
            if (s == null) {
                synchronized (this) {
                    s = servlet;
                    if (s == null) {
                        s = new RefineServlet();
                        s.init(new RefineServletConfig());
                        servlet = s;
                        logger.info("RefineServlet initialized on Solon container");
                    }
                }
            }
            return s;
        }

        /** 显式预热：等价 Jetty 的 {@code setInitOrder(1)}（部署期初始化），避免首个请求被阻塞。 */
        void warmup() throws ServletException {
            servlet();
        }

        @Override
        public void handle(Context ctx) throws Throwable {
            HttpServletRequest request = (HttpServletRequest) ctx.request();
            HttpServletResponse response = (HttpServletResponse) ctx.response();

            // 短路 Solon 自身的后续处理（路由/状态渲染）
            ctx.setHandled(true);

            servlet().service(new PathInfoRequest(request), response);
        }
    }

    /* -------------- servlet /*  语义桥（pathInfo） -------------- */

    static final class PathInfoRequest extends HttpServletRequestWrapper {

        /** Tomcat 对 multipart/form-data 会「按需解析表单体」；Jetty 默认不解析（无 multipart-config）。 */
        private final boolean multipart;
        private Map<String, String[]> queryParameters;

        PathInfoRequest(HttpServletRequest request) {
            super(request);
            String contentType = request.getContentType();
            this.multipart = contentType != null
                    && contentType.toLowerCase(Locale.ROOT).startsWith("multipart/form-data");
        }

        @Override
        public String getServletPath() {
            return "";
        }

        @Override
        public String getPathInfo() {
            String uri = getRequestURI();
            String ctxPath = getContextPath();
            if (ctxPath != null && !ctxPath.isEmpty() && uri.startsWith(ctxPath)) {
                return uri.substring(ctxPath.length());
            }
            return uri;
        }

        /*
         * 语义对齐（POC 关键点）：OR 的 CreateProjectCommand 先调 request.getParameterMap() 取 csrf_token，
         * 而 Tomcat 在 multipart 请求上会因此解析并**吞掉请求体**，令下游 commons-fileupload 拿不到上传文件
         * （表现为导入项目 0 行）。Jetty 不解析 multipart，故无此问题。这里对 multipart 只暴露查询串参数，
         * 把请求体留给 OR 自己的 fileupload 解析，与 Jetty 行为一致。
         */

        @Override
        public String getParameter(String name) {
            if (multipart) {
                String[] values = queryParameters().get(name);
                return (values == null || values.length == 0) ? null : values[0];
            }
            return super.getParameter(name);
        }

        @Override
        public String[] getParameterValues(String name) {
            if (multipart) {
                return queryParameters().get(name);
            }
            return super.getParameterValues(name);
        }

        @Override
        public Enumeration<String> getParameterNames() {
            if (multipart) {
                return Collections.enumeration(queryParameters().keySet());
            }
            return super.getParameterNames();
        }

        @Override
        public Map<String, String[]> getParameterMap() {
            if (multipart) {
                return Collections.unmodifiableMap(queryParameters());
            }
            return super.getParameterMap();
        }

        private Map<String, String[]> queryParameters() {
            if (queryParameters == null) {
                Map<String, String[]> map = new LinkedHashMap<>();
                String queryString = getQueryString();
                if (queryString != null && !queryString.isEmpty()) {
                    for (String pair : queryString.split("&")) {
                        if (pair.isEmpty()) {
                            continue;
                        }
                        int equal = pair.indexOf('=');
                        String name = (equal >= 0) ? pair.substring(0, equal) : pair;
                        String value = (equal >= 0) ? pair.substring(equal + 1) : "";
                        try {
                            name = URLDecoder.decode(name, "UTF-8");
                            value = URLDecoder.decode(value, "UTF-8");
                        } catch (UnsupportedEncodingException e) {
                            // UTF-8 一定可用
                        }
                        String[] previous = map.get(name);
                        if (previous == null) {
                            map.put(name, new String[] { value });
                        } else {
                            String[] merged = Arrays.copyOf(previous, previous.length + 1);
                            merged[previous.length] = value;
                            map.put(name, merged);
                        }
                    }
                }
                queryParameters = map;
            }
            return queryParameters;
        }
    }

    /* -------------- ServletConfig / ServletContext -------------- */

    static final class RefineServletConfig implements ServletConfig {

        private final ServletContext context = RefineServletContext.create();

        @Override
        public String getServletName() {
            return "refine";
        }

        @Override
        public ServletContext getServletContext() {
            return context;
        }

        @Override
        public String getInitParameter(String name) {
            // 与 RefineServer.configure() 注入的 init-param 对齐
            switch (name) {
                case "refine.data":
                    return RefineServer.getDataDir();
                case "butterfly.modules.path":
                    return RefineServer.getDataDir() + "/extensions";
                case "refine.autosave":
                    return Configurations.get("refine.autosave", "5");
                default:
                    // refine.version / refine.revision 走缺省逻辑（$VERSION / git.properties）
                    return null;
            }
        }

        @Override
        public Enumeration<String> getInitParameterNames() {
            return Collections.emptyEnumeration();
        }
    }

    /**
     * 最小 ServletContext：Butterfly 只用到 {@code getRealPath("/")}（定位 webapp）、
     * {@code getServerInfo()}（isGAE 判非空）、以及 {@code tempdir} 属性；其余按需兜底。
     */
    static final class RefineServletContext {

        static ServletContext create() {
            final File webapp = webappDir();
            final File temp = tempDir();
            final String serverInfo = "Solon/4.1.1 (tomcat)";

            logger.info("RefineServletContext: webapp={}, tempdir={}", webapp.getAbsolutePath(), temp.getAbsolutePath());

            return (ServletContext) Proxy.newProxyInstance(
                    RefineServletContext.class.getClassLoader(),
                    new Class<?>[] { ServletContext.class },
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "getRealPath": {
                                String path = (args == null || args[0] == null) ? "/" : String.valueOf(args[0]);
                                return new File(webapp, path.replaceFirst("^/+", "")).getAbsolutePath();
                            }
                            case "getServerInfo":
                                return serverInfo;
                            case "getAttribute": {
                                String key = String.valueOf(args[0]);
                                if (JAVAX_SERVLET_CONTEXT_TEMPDIR.equals(key)) {
                                    return temp;
                                }
                                return null;
                            }
                            case "setAttribute":
                            case "removeAttribute":
                                return null;
                            case "getAttributeNames":
                                return Collections.emptyEnumeration();
                            case "getResource": {
                                File f = new File(webapp, String.valueOf(args[0]).replaceFirst("^/+", ""));
                                return f.exists() ? f.toURI().toURL() : null;
                            }
                            case "getResourceAsStream": {
                                File f = new File(webapp, String.valueOf(args[0]).replaceFirst("^/+", ""));
                                return f.isFile() ? new FileInputStream(f) : null;
                            }
                            case "getContextPath":
                                return "";
                            case "getServletContextName":
                                return "JinRefine";
                            case "getInitParameter":
                                return null;
                            case "getInitParameterNames":
                                return Collections.emptyEnumeration();
                            case "getMajorVersion":
                            case "getEffectiveMajorVersion":
                                return 4;
                            case "getMinorVersion":
                            case "getEffectiveMinorVersion":
                                return 0;
                            case "toString":
                                return "RefineSolonServletContext";
                            case "hashCode":
                                return System.identityHashCode(proxy);
                            case "equals":
                                return proxy == args[0];
                            default:
                                return null;
                        }
                    });
        }

        static File webappDir() {
            File webapp = new File(Configurations.get("refine.webapp", "main/webapp"));
            if (isWebapp(webapp)) {
                return webapp;
            }
            webapp = new File("main/webapp");
            if (isWebapp(webapp)) {
                return webapp;
            }
            return new File("webapp");
        }

        static private boolean isWebapp(File dir) {
            if (dir == null || !dir.exists() || !dir.canRead()) {
                return false;
            }
            File webXml = new File(dir, "WEB-INF/web.xml");
            return webXml.exists() && webXml.canRead();
        }

        static File tempDir() {
            File dir = new File(RefineServer.getDataDir(), "temp");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            return dir;
        }
    }
}
