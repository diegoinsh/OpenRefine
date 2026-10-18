package com.jiongche.refine.kernel;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.testng.Assert;
import org.testng.annotations.Test;

public class ModuleDescriptorReaderTest {

    @Test
    public void readsAllFields() throws IOException {
        File moduleDir = Files.createTempDirectory("module-descriptor").toFile();
        File modInf = new File(moduleDir, "MOD-INF");
        Assert.assertTrue(modInf.mkdirs());
        Files.write(new File(modInf, "module.properties").toPath(), (
                "name = files\n"
                        + "implements = files, media\n"
                        + "requires = core\n"
                        + "before = data-quality\n"
                        + "after = records-db\n"
                        + "module-impl = com.example.ModuleImpl\n"
                        + "refine.spi = com.example.Spi\n"
                        + "templating.macros = macros.vm\n").getBytes(StandardCharsets.UTF_8));

        ModuleDescriptor descriptor = ModuleDescriptorReader.read(moduleDir);

        Assert.assertEquals(descriptor.getName(), "files");
        Assert.assertEquals(descriptor.getMountPoint(), "/extension/files/");
        Assert.assertEquals(descriptor.getImplementations().size(), 2);
        Assert.assertEquals(descriptor.getRequires().get(0), "core");
        Assert.assertEquals(descriptor.getBefore().get(0), "data-quality");
        Assert.assertEquals(descriptor.getAfter().get(0), "records-db");
        Assert.assertEquals(descriptor.getModuleImpl(), "com.example.ModuleImpl");
        Assert.assertEquals(descriptor.getSpiClass(), "com.example.Spi");
        Assert.assertEquals(descriptor.getMacros(), "macros.vm");
    }

    @Test
    public void coreMountIsRoot() throws IOException {
        File moduleDir = Files.createTempDirectory("core-descriptor").toFile();
        File modInf = new File(moduleDir, "MOD-INF");
        Assert.assertTrue(modInf.mkdirs());
        Files.write(new File(modInf, "module.properties").toPath(),
                "implements = core\n".getBytes(StandardCharsets.UTF_8));

        ModuleDescriptor descriptor = ModuleDescriptorReader.read(moduleDir);

        Assert.assertTrue(descriptor.isCore());
        Assert.assertEquals(descriptor.getMountPoint(), "/");
    }
}
