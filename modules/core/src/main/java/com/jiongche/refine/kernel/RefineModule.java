/*
 * JinRefine 容器改造 · 模块领域接口
 *
 * 在 ModuleView 之上补充生命周期与请求处理；Butterfly 与自研内核都实现该接口。
 */

package com.jiongche.refine.kernel;

import javax.servlet.ServletConfig;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public interface RefineModule extends ModuleView {

    ModuleDescriptor descriptor();

    void init(ServletConfig config) throws Exception;

    /**
     * 模块自定义请求处理。
     *
     * @param relativePath 相对挂载前缀的路径（core 的 "/" 会被剥成 "" / "index"）
     * @return true 表示已处理
     */
    boolean process(String relativePath, HttpServletRequest request, HttpServletResponse response) throws Exception;

    void destroy() throws Exception;
}
