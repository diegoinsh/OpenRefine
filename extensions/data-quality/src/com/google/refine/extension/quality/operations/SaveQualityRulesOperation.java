/*
 * Data Quality Extension - Save Quality Rules Operation
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

import com.google.refine.extension.quality.model.QualityRulesConfig;
import com.google.refine.history.Change;
import com.google.refine.history.HistoryEntry;
import com.google.refine.model.AbstractOperation;
import com.google.refine.model.ColumnsDiff;
import com.google.refine.model.Project;
import com.google.refine.util.ParsingUtilities;
import com.google.refine.util.Pool;

/**
 * Operation to save quality rules configuration to project overlay.
 */
public class SaveQualityRulesOperation extends AbstractOperation {

    @JsonIgnore
    public static final String operationDescription = "Save quality rules configuration";

    public static final String OVERLAY_MODEL_KEY = "qualityRulesConfig";

    @JsonProperty("rules")
    protected final QualityRulesConfig _rules;

    @JsonCreator
    public SaveQualityRulesOperation(
            @JsonProperty("rules") QualityRulesConfig rules) {
        this._rules = rules;
    }

    @Override
    protected String getBriefDescription(Project project) {
        return operationDescription;
    }

    @Override
    protected HistoryEntry createHistoryEntry(Project project, long historyEntryID)
            throws Exception {
        String description = operationDescription;
        Change change = new QualityRulesChange(_rules);
        return new HistoryEntry(historyEntryID, project, description, SaveQualityRulesOperation.this, change);
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
     * Change class for quality rules.
     */
    public static class QualityRulesChange implements Change {

        protected final QualityRulesConfig _newRules;
        protected QualityRulesConfig _oldRules = null;

        public QualityRulesChange(QualityRulesConfig newRules) {
            this._newRules = newRules;
        }

        @Override
        public void apply(Project project) {
            synchronized (project) {
                _oldRules = (QualityRulesConfig) project.overlayModels.get(OVERLAY_MODEL_KEY);
                project.overlayModels.put(OVERLAY_MODEL_KEY, _newRules);
            }
        }

        @Override
        public void revert(Project project) {
            synchronized (project) {
                if (_oldRules == null) {
                    project.overlayModels.remove(OVERLAY_MODEL_KEY);
                } else {
                    project.overlayModels.put(OVERLAY_MODEL_KEY, _oldRules);
                }
            }
        }

        @Override
        public void save(Writer writer, Properties options) throws IOException {
            writer.write("newRules=");
            writeRulesConfig(_newRules, writer);
            writer.write('\n');
            writer.write("oldRules=");
            writeRulesConfig(_oldRules, writer);
            writer.write('\n');
            writer.write("/ec/\n");
        }

        public static Change load(LineNumberReader reader, Pool pool) throws Exception {
            QualityRulesConfig oldRules = null;
            QualityRulesConfig newRules = null;

            String line;
            while ((line = reader.readLine()) != null && !"/ec/".equals(line)) {
                int equal = line.indexOf('=');
                CharSequence field = line.subSequence(0, equal);
                String value = line.substring(equal + 1);

                if ("oldRules".equals(field) && value.length() > 0) {
                    oldRules = ParsingUtilities.mapper.readValue(value, QualityRulesConfig.class);
                } else if ("newRules".equals(field) && value.length() > 0) {
                    newRules = ParsingUtilities.mapper.readValue(value, QualityRulesConfig.class);
                }
            }

            QualityRulesChange change = new QualityRulesChange(newRules);
            change._oldRules = oldRules;
            return change;
        }

        private static void writeRulesConfig(QualityRulesConfig rules, Writer writer) throws IOException {
            if (rules != null) {
                ParsingUtilities.defaultWriter.writeValue(writer, rules);
            }
        }
    }
}

