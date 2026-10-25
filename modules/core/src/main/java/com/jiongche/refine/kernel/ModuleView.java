/*
 * JinRefine 容器改造 · 模块最小只读视图
 *
 * OR 运行时真正依赖一个「模块」的只有：名称、目录、类加载器、资源定位、挂载前缀。
 * 把这个最小面抽成框架无关的接口，ButterflyModule 只是它的一个适配器；
 * 后续自研内核与 Solon 插件都实现该接口，避免业务代码绑死 Butterfly 类型。
 */

package com.jiongche.refine.kernel;

import java.io.File;
import java.net.URL;

public interface ModuleView {

    String getName();

    File getPath();

    ClassLoader getClassLoader();

    URL getResource(String path);

    /** 挂载前缀：core 为 "/"，扩展为 "/extension/{name}/"。 */
    String getMountPoint();
}
