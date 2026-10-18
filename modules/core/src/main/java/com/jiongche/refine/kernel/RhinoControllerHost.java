/*
 * JinRefine 容器改造 · Rhino 控制器宿主
 *
 * 复刻 ButterflyModuleImpl.scriptInit 的运行时契约：
 *   - 执行 MOD-INF/controller.js；
 *   - 注入 module / butterfly / logger 三个全局对象；
 *   - 预置 String.prototype.trim/startsWith/endsWith；
 *   - 调用 init()，请求时调用 process(path, request, response)。
 *
 * butterfly 只实现 OR 实际使用的 4 个方法：
 *   getWirings / sendString / sendTextFromTemplate / responded。
 */

package com.jiongche.refine.kernel;

import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.jiongche.refine.web.TemplateService;
import org.apache.velocity.VelocityContext;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.NativeObject;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RhinoControllerHost {

    private static final Logger logger = LoggerFactory.getLogger("refine-kernel");

    private static final String PRELUDE =
            "if (!String.prototype.trim) { String.prototype.trim = function() { return this.replace(/^\\s+|\\s+$/g, ''); }; }\n"
                    + "if (!String.prototype.startsWith) { String.prototype.startsWith = function(s, p) { return this.substr(p || 0, s.length) === s; }; }\n"
                    + "if (!String.prototype.endsWith) { String.prototype.endsWith = function(s, p) { var d = this.length - s.length; return d >= 0 && (p === undefined || p === d) && this.indexOf(s, d) !== -1; }; }\n";

    private final RefineModule module;
    private final ModuleRegistry registry;
    private final ButterflyShim butterflyShim = new ButterflyShim();

    private Scriptable scope;
    private Function initFunction;
    private Function processFunction;
    private Function destroyFunction;
    private volatile boolean responded;

    public RhinoControllerHost(RefineModule module, ModuleRegistry registry) {
        this.module = module;
        this.registry = registry;
    }

    public synchronized void init() {
        URL controller = module.getResource("MOD-INF/controller.js");
        if (controller == null) {
            logger.debug("No controller.js for module {}", module.getName());
            return;
        }
        Context cx = Context.enter();
        try {
            cx.setOptimizationLevel(-1);
            cx.setLanguageVersion(Context.VERSION_1_8);
            scope = cx.initStandardObjects();

            ScriptableObject.putProperty(scope, "module", Context.javaToJS(module, scope));
            ScriptableObject.putProperty(scope, "butterfly", Context.javaToJS(butterflyShim, scope));
            ScriptableObject.putProperty(scope, "logger", Context.javaToJS(logger, scope));

            cx.evaluateString(scope, PRELUDE, "butterfly-prelude", 1, null);
            try (Reader reader = new InputStreamReader(controller.openStream(), StandardCharsets.UTF_8)) {
                cx.evaluateReader(scope, reader, "controller.js", 1, null);
            }

            initFunction = function(cx, "init");
            processFunction = function(cx, "process");
            destroyFunction = function(cx, "destroy");

            if (initFunction != null) {
                initFunction.call(cx, scope, scope, new Object[0]);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot initialize controller.js for module " + module.getName(), e);
        } finally {
            Context.exit();
        }
    }

    public synchronized boolean process(String path, HttpServletRequest request, HttpServletResponse response)
            throws Exception {
        if (processFunction == null) {
            return false;
        }
        responded = false;
        Context cx = Context.enter();
        try {
            Object result = processFunction.call(cx, scope, scope, new Object[] { path, request, response });
            if (result instanceof Boolean) {
                return (Boolean) result;
            }
            return responded;
        } finally {
            Context.exit();
        }
    }

    public synchronized void destroy() {
        if (destroyFunction == null) {
            return;
        }
        Context cx = Context.enter();
        try {
            destroyFunction.call(cx, scope, scope, new Object[0]);
        } catch (Exception e) {
            logger.warn("controller.js destroy failed for module {}", module.getName(), e);
        } finally {
            Context.exit();
        }
    }

    private Function function(Context cx, String name) {
        Object value = scope.get(name, scope);
        return value instanceof Function ? (Function) value : null;
    }

    public final class ButterflyShim {

        public NativeObject getWirings(HttpServletRequest request) {
            NativeObject result = new NativeObject();
            Map<String, String> mounts = registry == null ? java.util.Collections.emptyMap() : registry.mounts();
            for (Map.Entry<String, String> entry : mounts.entrySet()) {
                result.defineProperty(entry.getKey(), entry.getValue(),
                        ScriptableObject.READONLY | ScriptableObject.PERMANENT);
            }
            return result;
        }

        public void sendString(HttpServletRequest request, HttpServletResponse response,
                Object text, Object encoding, Object mime) throws Exception {
            response.setContentType(String.valueOf(mime));
            response.setCharacterEncoding(String.valueOf(encoding));
            try (Writer writer = response.getWriter()) {
                writer.write(Context.toString(text));
            }
            responded = true;
        }

        public void sendTextFromTemplate(HttpServletRequest request, HttpServletResponse response,
                Scriptable context, Object template, Object encoding, Object mime) throws Exception {
            VelocityContext velocityContext = new VelocityContext();
            if (context != null) {
                for (Object id : context.getIds()) {
                    String key = String.valueOf(id);
                    Object value = ScriptableObject.getProperty(context, key);
                    velocityContext.put(key, Context.jsToJava(value, Object.class));
                }
            }
            if (!TemplateService.render(module, String.valueOf(template), velocityContext, response)) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND);
            }
            responded = true;
        }

        public void responded() {
            responded = true;
        }
    }
}
