/*
 * Data Quality Extension - Save Quality Result Operation
 */
package com.google.refine.extension.quality.operations;

import java.io.IOException;
import java.io.LineNumberReader;
import java.io.Writer;
import java.util.Collections;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import com.google.refine.extension.quality.model.CheckResult;
import com.google.refine.history.Change;
import com.google.refine.history.HistoryEntry;
import com.google.refine.model.AbstractOperation;
import com.google.refine.model.ColumnsDiff;
import com.google.refine.model.Project;
import com.google.refine.util.ParsingUtilities;
import com.google.refine.util.Pool;

/**
 * Operation to save quality check result to project overlay.
 */
public class SaveQualityResultOperation extends AbstractOperation {

    @JsonIgnore
    public static final String operationDescription = "Save quality check result";

    public static final String OVERLAY_MODEL_KEY = "qualityCheckResult";

    @JsonProperty("result")
    protected final CheckResult _result;

    @JsonCreator
    public SaveQualityResultOperation(
            @JsonProperty("result") CheckResult result) {
        this._result = result;
    }

    @Override
    protected String getBriefDescription(Project project) {
        return operationDescription;
    }

    @Override
    protected HistoryEntry createHistoryEntry(Project project, long historyEntryID)
            throws Exception {
        String description = operationDescription;
        Change change = new QualityResultChange(_result);
        return new HistoryEntry(historyEntryID, project, description, SaveQualityResultOperation.this, change);
    }

    @Override
    public Optional<Set<String>> getColumnDependencies() {
        return Optional.of(Collections.emptySet());
    }

    @Override
    public Optional<ColumnsDiff> getColumnsDiff() {
        return Optional.of(ColumnsDiff.empty());
    }

    /**
     * Change class for quality result.
     */
    public static class QualityResultChange implements Change {

        protected final CheckResult _newResult;
        protected CheckResult _oldResult = null;

        public QualityResultChange(CheckResult newResult) {
            this._newResult = newResult;
        }

        @Override
        public void apply(Project project) {
            synchronized (project) {
                _oldResult = (CheckResult) project.overlayModels.get(OVERLAY_MODEL_KEY);
                project.overlayModels.put(OVERLAY_MODEL_KEY, _newResult);
            }
        }

        @Override
        public void revert(Project project) {
            synchronized (project) {
                if (_oldResult == null) {
                    project.overlayModels.remove(OVERLAY_MODEL_KEY);
                } else {
                    project.overlayModels.put(OVERLAY_MODEL_KEY, _oldResult);
                }
            }
        }

        @Override
        public void save(Writer writer, Properties options) throws IOException {
            writer.write("newResult=");
            writeResult(_newResult, writer);
            writer.write('\n');
            writer.write("oldResult=");
            writeResult(_oldResult, writer);
            writer.write('\n');
            writer.write("/ec/\n");
        }

        public static Change load(LineNumberReader reader, Pool pool) throws Exception {
            CheckResult oldResult = null;
            CheckResult newResult = null;

            String line;
            while ((line = reader.readLine()) != null && !"/ec/".equals(line)) {
                int equal = line.indexOf('=');
                CharSequence field = line.subSequence(0, equal);
                String value = line.substring(equal + 1);

                if ("oldResult".equals(field) && value.length() > 0) {
                    oldResult = ParsingUtilities.mapper.readValue(value, CheckResult.class);
                } else if ("newResult".equals(field) && value.length() > 0) {
                    newResult = ParsingUtilities.mapper.readValue(value, CheckResult.class);
                }
            }

            QualityResultChange change = new QualityResultChange(newResult);
            change._oldResult = oldResult;
            return change;
        }

        private static void writeResult(CheckResult result, Writer writer) throws IOException {
            if (result != null) {
                ParsingUtilities.defaultWriter.writeValue(writer, result);
            }
        }
    }
}

