/*

Copyright 2024, Open Refine.
All rights reserved.
*/

package org.openrefine.extensions.files.importer;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.Set;

import com.google.refine.commands.CommandGuard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Vetoes mutating (Change-class) core commands while a batch extraction task
 * is running for the target project, so that background row appends never race
 * with user edits (design R-07, option A: project is read-only during
 * extraction). Browsing, read-only faceting and exports stay allowed.
 */
public class ExtractionChangeGuard implements CommandGuard {

    private static final Logger logger = LoggerFactory.getLogger(ExtractionChangeGuard.class);

    private static final String BUNDLE_NAME = "org.openrefine.extensions.files.importer.extraction-messages";
    private static final String KEY_IN_PROGRESS = "extraction.in-progress";

    // Change-class core commands (design 4.5.2: cell edits, expression transforms,
    // sort/reorder, add/remove rows or columns, star/flag annotations, history ops)
    private static final Set<String> CHANGE_COMMANDS = new HashSet<>(Arrays.asList(
            "apply-operations",
            "undo-redo",
            "edit-one-cell",
            "edit-one-command",
            "join-new-column",
            "add-column",
            "add-column-by-fetching-urls",
            "add-column-by-reconciliation",
            "remove-column",
            "rename-column",
            "move-column",
            "split-column",
            "reorder-columns",
            "denormalize",
            "transpose-columns-into-rows",
            "transpose-rows-into-columns",
            "remove-rows",
            "reorder-rows",
            "annotate-one-row",
            "save-sorting",
            "delete-project"));

    private static final ExtractionChangeGuard INSTANCE = new ExtractionChangeGuard();

    public static ExtractionChangeGuard get() {
        return INSTANCE;
    }

    /** Registers this guard with the servlet (idempotent). */
    public static void register(com.google.refine.RefineServlet servlet) {
        servlet.registerCommandGuard(INSTANCE);
        logger.info("ExtractionChangeGuard registered: core Change commands are vetoed while batch extraction runs");
    }

    /** Veto message for the given client locale (falls back to English, not the server default). */
    public static String vetoMessage(Locale locale) {
        try {
            Locale effective = locale != null ? locale : Locale.ROOT;
            ResourceBundle bundle = ResourceBundle.getBundle(BUNDLE_NAME, effective,
                    ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
            return bundle.getString(KEY_IN_PROGRESS);
        } catch (Exception e) {
            logger.warn("Veto message bundle unavailable, falling back to key: {}", KEY_IN_PROGRESS);
            return KEY_IN_PROGRESS;
        }
    }

    @Override
    public String intercept(String module, String commandName, String projectId, Locale locale) {
        if (projectId == null || projectId.isEmpty()) {
            return null;
        }
        long pid;
        try {
            pid = Long.parseLong(projectId.trim());
        } catch (NumberFormatException e) {
            return null;
        }
        BatchExtractionManager.Task task = BatchExtractionManager.get().getTask(pid);
        if (task == null || !BatchExtractionManager.STATUS_RUNNING.equals(task.status)) {
            return null;
        }
        if ("core".equals(module) && CHANGE_COMMANDS.contains(commandName)) {
            logger.info("Vetoed core/{} during batch extraction of project {}", commandName, pid);
            return vetoMessage(locale);
        }
        return null;
    }
}
