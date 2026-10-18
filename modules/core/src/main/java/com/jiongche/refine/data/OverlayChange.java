/*
 * JinRefine · OR 数据源适配器（P0）
 */

package com.jiongche.refine.data;

import java.io.IOException;
import java.io.LineNumberReader;
import java.io.Writer;
import java.util.Properties;

import com.google.refine.history.Change;
import com.google.refine.model.OverlayModel;
import com.google.refine.model.Project;
import com.google.refine.util.ParsingUtilities;
import com.google.refine.util.Pool;

/**
 * 通用 overlay 变更（供 SDK 数据源驱动的 {@code putOverlayJson} 使用）。
 *
 * <p>把「按名换入一个 overlay 模型」包装为 OR {@link Change}：{@code apply} 记下旧模型并换入新模型，
 * {@code revert} 还原；落盘/加载走 OR 既有的 Change 文本格式（{@code new=} / {@code old=} + {@code /ec/}）。
 */
public class OverlayChange implements Change {

    private final String name;
    private final OverlayModel newModel;
    private OverlayModel oldModel;
    private boolean hadOld;

    public OverlayChange(String name, OverlayModel newModel) {
        this.name = name;
        this.newModel = newModel;
    }

    @Override
    public void apply(Project project) {
        synchronized (project) {
            hadOld = project.overlayModels.containsKey(name);
            oldModel = project.overlayModels.get(name);
            if (newModel == null) {
                project.overlayModels.remove(name);
            } else {
                project.overlayModels.put(name, newModel);
            }
        }
    }

    @Override
    public void revert(Project project) {
        synchronized (project) {
            if (hadOld) {
                project.overlayModels.put(name, oldModel);
            } else {
                project.overlayModels.remove(name);
            }
        }
    }

    @Override
    public void save(Writer writer, Properties options) throws IOException {
        writer.write("name=");
        writer.write(name);
        writer.write('\n');
        writer.write("new=");
        writeModel(writer, newModel);
        writer.write('\n');
        writer.write("old=");
        writeModel(writer, oldModel);
        writer.write('\n');
        writer.write("/ec/\n");
    }

    static public Change load(LineNumberReader reader, Pool pool) throws Exception {
        String name = null;
        OverlayModel oldModel = null;
        OverlayModel newModel = null;

        String line;
        while ((line = reader.readLine()) != null && !"/ec/".equals(line)) {
            int equal = line.indexOf('=');
            if (equal < 0) {
                continue;
            }
            String field = line.substring(0, equal);
            String value = line.substring(equal + 1);
            if ("name".equals(field)) {
                name = value;
            } else if ("new".equals(field) && value.length() > 0) {
                newModel = readModel(name, value);
            } else if ("old".equals(field) && value.length() > 0) {
                oldModel = readModel(name, value);
            }
        }

        OverlayChange change = new OverlayChange(name, newModel);
        change.oldModel = oldModel;
        change.hadOld = oldModel != null;
        return change;
    }

    private static void writeModel(Writer writer, OverlayModel model) throws IOException {
        if (model != null) {
            ParsingUtilities.defaultWriter.writeValue(writer, model);
        }
    }

    private static OverlayModel readModel(String name, String json) throws IOException {
        Class<? extends OverlayModel> klass = Project.overlayModelClass(name);
        if (klass == null) {
            throw new IOException("未注册的 overlay 模型: " + name);
        }
        return ParsingUtilities.mapper.readValue(json, klass);
    }
}
