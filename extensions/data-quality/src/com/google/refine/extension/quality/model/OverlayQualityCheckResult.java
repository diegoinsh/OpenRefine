/*
 * Data Quality Extension - OR 侧 overlay 薄壳（P2）
 *
 * 内核实体 {@link CheckResult} 已下沉到 data-quality-engine（零 OR 依赖，故不再
 * implements OverlayModel）。OR 侧需要它作为 {@code Project#overlayModels} 的槽值，故此处
 * 提供仅承载 OverlayModel 三空钩子的子类，供 SPI 注册用。子类不新增任何 JSON 属性，
 * 序列化产物与下沉前**逐字节等价**（缺省零差异）。
 */
package com.google.refine.extension.quality.model;

import com.google.refine.model.OverlayModel;
import com.google.refine.model.Project;

/** {@link CheckResult} 的 OR overlay 子类（overlay 槽名 {@code qualityCheckResult}）。 */
public class OverlayQualityCheckResult extends CheckResult implements OverlayModel {

    public OverlayQualityCheckResult() {
        super();
    }

    public OverlayQualityCheckResult(String checkType) {
        super(checkType);
    }

    @Override
    public void onBeforeSave(Project project) {
    }

    @Override
    public void onAfterSave(Project project) {
    }

    @Override
    public void dispose(Project project) {
    }
}
