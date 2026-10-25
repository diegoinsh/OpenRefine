package com.jiongche.refine.kernel;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

public class ModuleGraphTest {

    private static ModuleDescriptor descriptor(String name, String extended,
            List<String> requires, List<String> before, List<String> after) {
        return new ModuleDescriptor(name, new File(name), extended,
                name.equals("core") ? Collections.singletonList("core") : Collections.emptyList(),
                requires, before, after, null, null, null);
    }

    @Test
    public void ordersByRequiresAndExtends() {
        List<ModuleDescriptor> descriptors = Arrays.asList(
                descriptor("data-quality", null, Collections.singletonList("core"), Collections.emptyList(),
                        Collections.emptyList()),
                descriptor("core", null, Collections.emptyList(), Collections.emptyList(), Collections.emptyList()),
                descriptor("extended", "core", Collections.emptyList(), Collections.emptyList(), Collections.emptyList()));

        List<String> order = ModuleGraph.order(descriptors);

        Assert.assertTrue(order.indexOf("core") < order.indexOf("data-quality"));
        Assert.assertTrue(order.indexOf("core") < order.indexOf("extended"));
    }

    @Test
    public void honorsBeforeAndAfter() {
        List<ModuleDescriptor> descriptors = Arrays.asList(
                descriptor("a", null, Collections.emptyList(), Collections.singletonList("b"),
                        Collections.emptyList()),
                descriptor("b", null, Collections.emptyList(), Collections.emptyList(),
                        Collections.emptyList()));

        Assert.assertEquals(ModuleGraph.order(descriptors), Arrays.asList("a", "b"));
    }

    @Test(expectedExceptions = IllegalStateException.class)
    public void rejectsCycle() {
        List<ModuleDescriptor> descriptors = Arrays.asList(
                descriptor("a", null, Collections.singletonList("b"), Collections.emptyList(), Collections.emptyList()),
                descriptor("b", null, Collections.singletonList("a"), Collections.emptyList(), Collections.emptyList()));

        ModuleGraph.order(descriptors);
    }
}
