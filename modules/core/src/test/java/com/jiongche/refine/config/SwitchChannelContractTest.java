/*
 * JinRefine · 启动开关通道契约
 *
 * refine.bat / refine 必须把同一组开关以 -D 形式传给 JVM；
 * A7 删除装载器双实现后，refine.module.loader 不应再出现在任何入口。
 */

package com.jiongche.refine.config;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.testng.annotations.Test;

public class SwitchChannelContractTest {

    private static String read(String name) throws IOException {
        File file = new File("../../", name);
        assertTrue(file.isFile(), "启动脚本缺失: " + file.getAbsolutePath());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void windowsLauncherForwardsSwitchChannels() throws IOException {
        String bat = read("refine.bat");
        assertTrue(bat.contains("-Drefine.port=%REFINE_PORT%"), "refine.bat 缺少 port 通道");
        assertTrue(bat.contains("-Drefine.autosave=%REFINE_AUTOSAVE_PERIOD%"), "refine.bat 缺少 autosave 通道");
        assertTrue(bat.contains("-D%REFINE_EXTRA_OPTS%"), "refine.bat 缺少 extra-opts 通道（license 等开关）");
    }

    @Test
    public void posixLauncherForwardsSwitchChannels() throws IOException {
        String sh = read("refine");
        assertTrue(sh.contains("-Drefine.port=$REFINE_PORT"), "refine 缺少 port 通道");
        assertTrue(sh.contains("-Drefine.autosave=$REFINE_AUTOSAVE_PERIOD"), "refine 缺少 autosave 通道");
        assertTrue(sh.contains("REFINE_EXTRA_OPTS"), "refine 缺少 extra-opts 通道（license 等开关）");
    }

    @Test
    public void loaderSwitchIsRetired() throws IOException {
        assertFalse(read("refine.bat").contains("refine.module.loader"), "refine.bat 仍透传已删除的装载器开关");
        assertFalse(read("refine").contains("refine.module.loader"), "refine 仍透传已删除的装载器开关");
        assertFalse(read("refine.ini").contains("refine.module.loader"), "refine.ini 仍保留已删除的装载器开关");
    }
}
