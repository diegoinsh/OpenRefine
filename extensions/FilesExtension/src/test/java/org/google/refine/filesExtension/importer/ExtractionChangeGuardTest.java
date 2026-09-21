package org.google.refine.filesExtension.importer;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.openrefine.extensions.files.importer.BatchExtractionManager;
import org.openrefine.extensions.files.importer.BatchExtractionManager.Task;
import org.openrefine.extensions.files.importer.ExtractionChangeGuard;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

public class ExtractionChangeGuardTest {

    private static final String TASKS_FIELD = "tasks";
    private static final long PID = 999801L;
    private Task injected;

    @AfterMethod
    public void cleanup() throws Exception {
        if (injected != null) {
            tasksMap().remove(PID);
            injected = null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<Long, Task> tasksMap() throws Exception {
        Field f = BatchExtractionManager.class.getDeclaredField(TASKS_FIELD);
        f.setAccessible(true);
        Map<Long, Task> map = (Map<Long, Task>) f.get(BatchExtractionManager.get());
        if (map == null) {
            map = new ConcurrentHashMap<>();
            f.set(BatchExtractionManager.get(), map);
        }
        return map;
    }

    private void injectRunningTask() {
        Task task = new Task(PID, "n/a", null, Collections.emptyList(), Collections.emptyList(), false);
        Assert.assertEquals(task.status, BatchExtractionManager.STATUS_RUNNING);
        injected = task;
        try {
            tasksMap().put(PID, task);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private ExtractionChangeGuard guard() {
        return ExtractionChangeGuard.get();
    }

    @Test
    public void allowsChangeCommandWhenNoTask() {
        Assert.assertNull(guard().intercept("core", "edit-one-cell", String.valueOf(PID), null));
    }

    @Test
    public void allowsChangeCommandWhenTaskNotRunning() {
        injectRunningTask();
        injected.status = BatchExtractionManager.STATUS_COMPLETED;
        Assert.assertNull(guard().intercept("core", "edit-one-cell", String.valueOf(PID), null));
    }

    @Test
    public void vetoesChangeCommandsWhileRunning() {
        injectRunningTask();
        String[] changeCommands = {
                "apply-operations", "undo-redo", "edit-one-cell", "join-new-column",
                "add-column", "remove-column", "rename-column", "move-column",
                "split-column", "reorder-columns", "reorder-rows", "remove-rows",
                "annotate-one-row", "transpose-rows-into-columns", "delete-project"};
        for (String cmd : changeCommands) {
            Assert.assertNotNull(guard().intercept("core", cmd, String.valueOf(PID), null), cmd);
        }
    }

    @Test
    public void vetoMessageLocalized() {
        injectRunningTask();
        String zh = guard().intercept("core", "edit-one-cell", String.valueOf(PID),
                java.util.Locale.SIMPLIFIED_CHINESE);
        String en = guard().intercept("core", "edit-one-cell", String.valueOf(PID),
                java.util.Locale.ENGLISH);
        StringBuilder codes = new StringBuilder();
        for (int i = 0; i < Math.min(8, zh.length()); i++) {
            codes.append(Integer.toHexString(zh.charAt(i))).append(' ');
        }
        Assert.assertTrue(zh.startsWith("\u6279\u91cf\u9898\u540d\u63d0\u53d6"),
                "len=" + zh.length() + " codes=[" + codes + "] zh=" + zh);
        Assert.assertTrue(en.contains("Batch extraction is in progress"), en);
    }

    @Test
    public void allowsReadOnlyCommandsWhileRunning() {
        injectRunningTask();
        String[] readOnly = {"get-rows", "get-columns-info", "compute-facets", "export-rows", "get-models"};
        for (String cmd : readOnly) {
            Assert.assertNull(guard().intercept("core", cmd, String.valueOf(PID), null), cmd);
        }
    }

    @Test
    public void allowsNonCoreCommandsWhileRunning() {
        injectRunningTask();
        Assert.assertNull(guard().intercept("files", "batch-extraction", String.valueOf(PID), null));
    }

    @Test
    public void allowsInvalidProjectId() {
        injectRunningTask();
        Assert.assertNull(guard().intercept("core", "edit-one-cell", "not-a-number", null));
        Assert.assertNull(guard().intercept("core", "edit-one-cell", null, null));
    }
}
