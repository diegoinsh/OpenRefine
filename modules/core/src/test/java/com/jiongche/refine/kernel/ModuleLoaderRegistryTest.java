package com.jiongche.refine.kernel;

import com.jiongche.refine.loader.ModuleLoaderRegistry;
import org.testng.Assert;
import org.testng.annotations.Test;

public class ModuleLoaderRegistryTest {

    @Test
    public void resolvesAllThreeValues() {
        Assert.assertEquals(ModuleLoaderRegistry.resolve("butterfly").id(), "butterfly");
        Assert.assertEquals(ModuleLoaderRegistry.resolve("solon").id(), "solon");
        Assert.assertEquals(ModuleLoaderRegistry.resolve("solon-plugin").id(), "solon-plugin");
    }

    @Test
    public void unknownValueFallsBackToButterfly() {
        Assert.assertEquals(ModuleLoaderRegistry.resolve("not-a-loader").id(), "butterfly");
    }
}
