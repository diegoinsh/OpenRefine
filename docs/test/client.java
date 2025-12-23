package com.deeppaas.rule.factory.impl.rule;

import com.deeppaas.FileEnum;
import com.deeppaas.common.helper.*;
import com.deeppaas.result.entity.TaskErrorResultDO;
import com.deeppaas.result.enums.ErrorResultType;

import com.deeppaas.rule.dto.PublicRuleDTO;
import com.deeppaas.rule.factory.base.RuleExecuteFactoryBase;
import com.deeppaas.rule.service.RuleExecuteFactoryService;
import com.deeppaas.task.config.dto.ProjectTaskConfigDTO;
import com.deeppaas.task.config.service.ProjectTaskConfigService;
import com.deeppaas.task.data.dto.ProjectTaskFormDataDTO;
import com.deeppaas.task.data.dto.ProjectTaskImageDataDTO;
import com.deeppaas.task.data.dto.ProjectTaskPdfDataDTO;
import com.deeppaas.task.data.service.ProjectTaskFormDataService;
import com.deeppaas.task.info.dto.ProjectTaskInfoDTO;
import com.deeppaas.task.info.service.ProjectTaskInfoService;

import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.math.BigDecimal;
import java.util.*;

/**
 * 档案要素检查规则执行器
 * 支持图像分件处理和智能要素提取比对
 */
@Service("rule_execute_archiveElements")
public class ArchiveElementsCheckRule extends RuleExecuteFactoryBase implements RuleExecuteFactoryService {
    /**
     * 图像分件方式枚举
     */
    public enum ImageGroupingType {
        SINGLE_PIECE,           // 全部图像作为一件
        START_END_PAGE,         // 首页号+尾页号
        START_PAGE_COUNT,       // 首页号+页数
        PAGE_RANGE             // 起止页号（如"1-5"）
    }

    @Autowired
    private ProjectTaskConfigService projectTaskConfigService;
    
    @Autowired
    private ProjectTaskFormDataService projectTaskFormDataService;

    @Autowired
    private ProjectTaskInfoService projectTaskInfoService;

    @Override
    public List<TaskErrorResultDO> ruleExecute(PublicRuleDTO ruleDTO, 
                                             List<ProjectTaskFormDataDTO> formDataDTOList, 
                                             List<ProjectTaskImageDataDTO> taskImageDataDTOList, 
                                             ProjectTaskInfoDTO projectTaskInfoDTO, 
                                             ProjectTaskPdfDataDTO taskPdfDataDTO, 
                                             ProjectTaskConfigDTO taskConfigDTO) {
        
        List<TaskErrorResultDO> errorResults = new ArrayList<>();
        
        try {
            String taskId = projectTaskInfoDTO.getId();

            System.out.println("🔍🔍🔍 === 开始档案要素检查 === 🔍🔍🔍");
            System.out.println("📋 任务ID: " + taskId);
            System.out.println("📋 配置ID: " + taskConfigDTO.getId());
            System.out.println("📋 Excel条目数量: " + (formDataDTOList != null ? formDataDTOList.size() : 0));
            System.out.println("📋 图像数据数量: " + (taskImageDataDTOList != null ? taskImageDataDTOList.size() : 0));

            // 🔍 调试：显示原始图像数据
            // System.out.println("🔍🔍🔍 === 原始图像数据详情 === 🔍🔍🔍");
            // if (taskImageDataDTOList != null) {
            //     for (int i = 0; i < Math.min(taskImageDataDTOList.size(), 5); i++) {
            //         ProjectTaskImageDataDTO imageData = taskImageDataDTOList.get(i);
            //         System.out.println(String.format("原始图像数据[%d]:", i));
            //         System.out.println("  - ID: " + imageData.getId());
            //         System.out.println("  - dataKey: " + imageData.getDataKey());
            //         System.out.println("  - partNumber: " + imageData.getPartNumber());
            //         System.out.println("  - imageFilePath: " + imageData.getImageFilePath());
            //     }
            // }

            // 🔍 空值检查
            if (formDataDTOList == null || formDataDTOList.isEmpty()) {
                System.err.println("❌ Excel数据为空，无法执行档案要素检查");
                return errorResults;
            }

            if (taskImageDataDTOList == null || taskImageDataDTOList.isEmpty()) {
                System.err.println("❌ 图像数据为空，无法执行档案要素检查");
                return errorResults;
            }

            // 🔍 1. 检查是否需要图像分件处理
            ImageGroupingResult groupingResult = checkImageGroupingNeeded(formDataDTOList, taskConfigDTO);
            System.out.println("🔍 分件检查结果: " + groupingResult.getGroupingType());

            List<ProjectTaskImageDataDTO> processedImageList = taskImageDataDTOList;

            if (groupingResult.needsGrouping()) {
                System.out.println("🚀 开始执行图像分件处理");
                // 🔍 2. 执行图像分件处理（内存中处理，不存数据库）
                processedImageList = processImageGroupingInMemory(formDataDTOList, taskImageDataDTOList, taskConfigDTO, groupingResult);
                System.out.println("📊 分件处理完成，处理后图像组数量: " + processedImageList.size());
            } else {
                System.out.println("📝 无需分件处理，使用原始图像数据");
            }

            // 🔍 3. 解析规则参数配置
            double confidenceThreshold = 0.5; // 默认值
            double similarityThreshold = 0.8; // 默认值
            boolean enableStampProcessing = true; // 默认值
            double stampConfidenceThreshold = 0.5; // 默认值
            boolean enablePreprocessing = true; // 默认值
            List<String> selectedElements = Arrays.asList("title", "responsible_party", "document_number", "issue_date"); // 默认要素

            if (ruleDTO != null && ruleDTO.getRuleValue() != null) {
                try {
                    Map<String, Object> ruleParams = JsonHelper.json2map(ruleDTO.getRuleValue());
                    System.out.println("🔍 规则参数配置: " + ruleParams);

                    // 🔍 获取选择的要素列表
                    if (ruleParams.containsKey("selectedElements")) {
                        Object elementsValue = ruleParams.get("selectedElements");
                        if (elementsValue instanceof List) {
                            selectedElements = (List<String>) elementsValue;
                        } else if (elementsValue instanceof String) {
                            // 如果是字符串，尝试解析为JSON数组
                            try {
                                selectedElements = JsonHelper.readToLists((String) elementsValue, String.class);
                            } catch (Exception e) {
                                System.err.println("解析selectedElements字符串失败: " + e.getMessage());
                            }
                        }
                    }

                    // 获取其他参数...
                    if (ruleParams.containsKey("confidence_threshold")) {
                        Object confValue = ruleParams.get("confidence_threshold");
                        if (confValue instanceof Number) {
                            confidenceThreshold = ((Number) confValue).doubleValue();
                        } else if (confValue instanceof String) {
                            confidenceThreshold = Double.parseDouble((String) confValue);
                        }
                    }

                    if (ruleParams.containsKey("similarity_threshold")) {
                        Object simValue = ruleParams.get("similarity_threshold");
                        if (simValue instanceof Number) {
                            similarityThreshold = ((Number) simValue).doubleValue();
                        } else if (simValue instanceof String) {
                            similarityThreshold = Double.parseDouble((String) simValue);
                        }
                    }

                    if (ruleParams.containsKey("enable_stamp_processing")) {
                        Object stampValue = ruleParams.get("enable_stamp_processing");
                        if (stampValue instanceof Boolean) {
                            enableStampProcessing = (Boolean) stampValue;
                        } else if (stampValue instanceof String) {
                            enableStampProcessing = Boolean.parseBoolean((String) stampValue);
                        }
                    }

                    if (ruleParams.containsKey("stamp_confidence_threshold")) {
                        Object stampConfValue = ruleParams.get("stamp_confidence_threshold");
                        if (stampConfValue instanceof Number) {
                            stampConfidenceThreshold = ((Number) stampConfValue).doubleValue();
                        } else if (stampConfValue instanceof String) {
                            stampConfidenceThreshold = Double.parseDouble((String) stampConfValue);
                        }
                    }

                    if (ruleParams.containsKey("enable_preprocessing")) {
                        Object prepValue = ruleParams.get("enable_preprocessing");
                        if (prepValue instanceof Boolean) {
                            enablePreprocessing = (Boolean) prepValue;
                        } else if (prepValue instanceof String) {
                            enablePreprocessing = Boolean.parseBoolean((String) prepValue);
                        }
                    }

                } catch (Exception e) {
                    System.err.println("解析规则参数失败，使用默认值: " + e.getMessage());
                }
            }

            System.out.println("🔍 使用的参数配置:");
            System.out.println("  selectedElements: " + selectedElements);
            System.out.println("  confidence_threshold: " + confidenceThreshold);
            System.out.println("  similarity_threshold: " + similarityThreshold);
            System.out.println("  enable_stamp_processing: " + enableStampProcessing);
            System.out.println("  stamp_confidence_threshold: " + stampConfidenceThreshold);
            System.out.println("  enable_preprocessing: " + enablePreprocessing);

            // 🔍 4. 继续执行档案要素检查逻辑（使用处理后的图像数据）
            System.out.println("📋 开始档案要素提取和比对");
            System.out.println("📋 使用图像数据数量: " + (processedImageList != null ? processedImageList.size() : 0));

            // 🔍 解析规则模板配置获取字段映射（基于选择的要素）
            Map<String, String> elementToExcelFieldMap = getElementToExcelFieldMapping(ruleDTO, taskConfigDTO, selectedElements);
            System.out.println("档案要素字段映射: " + elementToExcelFieldMap);

            // 🔍 准备Excel数据
            List<Map<String, Object>> excelData = new ArrayList<>();
            for (ProjectTaskFormDataDTO formData : formDataDTOList) {
                if (Objects.equals(formData.getTaskConfigId(), taskConfigDTO.getId())) {
                    Map<String, Object> rowData = new HashMap<>();
                    rowData.put("dataKey", formData.getDataKey());
                    rowData.put("partNumber", formData.getPartNumber()); // 🔍 添加件号信息
                    rowData.put("rowNum", formData.getRowNum());

                    System.out.println(String.format("📋 Excel数据行: dataKey=%s, partNumber=%s, rowNum=%d",
                        formData.getDataKey(), formData.getPartNumber(), formData.getRowNum()));

                    // 🔍 根据规则配置的字段映射获取Excel数据
                    String taskJson = formData.getTaskJson();
                    if (StringHelper.isNotEmpty(taskJson)) {
                        Map<String, Object> taskData = JsonHelper.json2map(taskJson);

                        // 使用动态字段映射
                        for (Map.Entry<String, String> mapping : elementToExcelFieldMap.entrySet()) {
                            String elementKey = mapping.getKey();     // 如: "title"
                            String excelFieldName = mapping.getValue(); // 如: "题名"
                            Object excelValue = taskData.get(excelFieldName);
                            rowData.put(elementKey, excelValue);
                            System.out.println(String.format("字段映射 - %s -> %s: [%s]", elementKey, excelFieldName, excelValue));
                        }
                    }
                    excelData.add(rowData);
                }
            }

            // 🖼️ 准备图像文件路径
            List<String> imagePaths = new ArrayList<>();

            System.out.println("🖼️🖼️🖼️ === 收集图像路径信息 === 🖼️🖼️🖼️");
            System.out.println("processedImageList数量: " + processedImageList.size());

            for (int i = 0; i < processedImageList.size(); i++) {
                ProjectTaskImageDataDTO imageData = processedImageList.get(i);
                System.out.println(String.format("图像数据[%d]:", i));
                System.out.println("  - ID: " + imageData.getId());
                System.out.println("  - dataKey: " + imageData.getDataKey());
                System.out.println("  - partNumber: " + imageData.getPartNumber());
                System.out.println("  - imageFilePath: " + imageData.getImageFilePath());
                System.out.println("  - imageNames: " + imageData.getImageNames());
                System.out.println("  - imageCount: " + imageData.getImageCount());

                if (StringHelper.isNotEmpty(imageData.getImageFilePath())) {
                    imagePaths.add(imageData.getImageFilePath());
                    System.out.println("  ✅ 添加到图像路径列表: " + imageData.getImageFilePath());
                } else {
                    System.out.println("  ❌ 图像路径为空，跳过");
                }
            }

            System.out.println("📊 最终图像路径列表:");
            for (int i = 0; i < imagePaths.size(); i++) {
                System.out.println(String.format("  [%d] %s", i, imagePaths.get(i)));
            }
            System.out.println("🖼️🖼️🖼️ === 图像路径收集完成 === 🖼️🖼️🖼️");

            if (excelData.isEmpty() || imagePaths.isEmpty()) {
                System.err.println("❌ 数据检查失败:");
                System.err.println("  Excel数据为空: " + excelData.isEmpty());
                System.err.println("  图像路径为空: " + imagePaths.isEmpty());
                return errorResults; // 没有数据需要处理
            }

            // 🚀 调用Python AI服务 - 传递完整的分件数据
            ArchiveElementsCheckResult result = callPythonArchiveService(taskId, excelData, processedImageList,
                selectedElements, confidenceThreshold, similarityThreshold, enableStampProcessing,
                stampConfidenceThreshold, enablePreprocessing);

            if (result != null && result.isSuccess()) {
                // 📝 处理检查结果，转换为TaskErrorResultDO列表
                errorResults = convertToTaskErrorResults(result, taskConfigDTO, projectTaskInfoDTO, ruleDTO);
            }

        } catch (Exception e) {
            // 记录错误但不中断整个流程
            System.err.println("档案要素检查失败: " + e.getMessage());
            e.printStackTrace();
        }

        // 🔍 档案要素检查完成，更新任务进度
        String taskId = projectTaskInfoDTO.getId();
        System.out.println("🔍 档案要素检查完成，更新进度");
        projectTaskInfoService.updateTaskProcess(taskId, new BigDecimal(0), FileEnum.ARCHIVE_ELEMENTS.getNum());

        return errorResults;
    }

    /**
     * 检查是否需要图像分件处理
     */
    private ImageGroupingResult checkImageGroupingNeeded(List<ProjectTaskFormDataDTO> formDataList,
                                                                  ProjectTaskConfigDTO taskConfigDTO) {
        System.out.println("🔍🔍🔍 === 开始检测图像分件方式 === 🔍🔍🔍");

        // 🔍 从字段库配置中查找关键字段
        Map<String, String> fieldMapping = taskConfigDTO.buildRuleMappingMap();

        System.out.println("📋 字段映射信息:");
        if (fieldMapping == null || fieldMapping.isEmpty()) {
            System.out.println("  ❌ 字段映射为空，使用单件处理模式");
            return new ImageGroupingResult(ImageGroupingType.SINGLE_PIECE);
        } else {
            System.out.println("  ✅ 字段映射数量: " + fieldMapping.size());
            fieldMapping.forEach((key, value) ->
                    System.out.println("    " + key + " -> " + value));
        }

        // 🔍 检查是否存在分件相关字段
        boolean hasStartPage = containsFieldValue(fieldMapping, "首页号")||containsFieldValue(fieldMapping, "起始页号");
        boolean hasEndPage = containsFieldValue(fieldMapping, "尾页号")||containsFieldValue(fieldMapping, "终止页号");
        boolean hasPageCount = containsFieldValue(fieldMapping, "页数");
        boolean hasPageRange = containsFieldValue(fieldMapping, "起止页号")||containsFieldValue(fieldMapping, "起讫页号")||containsFieldValue(fieldMapping, "首尾页号");

        System.out.println("🔍 分件字段检测结果:");
        System.out.println("  首页号: " + (hasStartPage ? "✅ 存在" : "❌ 不存在"));
        System.out.println("  尾页号: " + (hasEndPage ? "✅ 存在" : "❌ 不存在"));
        System.out.println("  页数: " + (hasPageCount ? "✅ 存在" : "❌ 不存在"));
        System.out.println("  起止页号: " + (hasPageRange ? "✅ 存在" : "❌ 不存在"));

        // 🔍 创建结果对象
        ImageGroupingResult result;

        // 🔍 按优先级确定处理方式
        if (hasPageRange) {
            System.out.println("🎯 检测到起止页号字段，使用PAGE_RANGE模式");
            result = new ImageGroupingResult(ImageGroupingType.PAGE_RANGE);
            // 🔍 按优先级查找页号范围字段
            String pageRangeField = getFieldIdByMultipleValues(fieldMapping,
                    "起止页号", "起讫页号", "首尾页号");
            result.setPageRangeField(pageRangeField);
            System.out.println("📋 使用页号范围字段: " + pageRangeField);
        } else if (hasStartPage && hasEndPage) {
            System.out.println("🎯 检测到首页号+尾页号字段，使用START_END_PAGE模式");
            result = new ImageGroupingResult(ImageGroupingType.START_END_PAGE);
            // 🔍 按优先级查找首页号字段
            String startPageField = getFieldIdByMultipleValues(fieldMapping,
                    "首页号", "起始页号");
            // 🔍 按优先级查找尾页号字段
            String endPageField = getFieldIdByMultipleValues(fieldMapping,
                    "尾页号", "终止页号");
            result.setStartPageField(startPageField);
            result.setEndPageField(endPageField);
            System.out.println("📋 使用首页号字段: " + startPageField);
            System.out.println("📋 使用尾页号字段: " + endPageField);
        } else if (hasStartPage && hasPageCount) {
            System.out.println("🎯 检测到首页号+页数字段，使用START_PAGE_COUNT模式");
            result = new ImageGroupingResult(ImageGroupingType.START_PAGE_COUNT);
            // 🔍 按优先级查找首页号字段
            String startPageField = getFieldIdByMultipleValues(fieldMapping,
                    "首页号", "起始页号");
            String pageCountField = getFieldIdByValue(fieldMapping, "页数");
            result.setStartPageField(startPageField);
            result.setPageCountField(pageCountField);
            System.out.println("📋 使用首页号字段: " + startPageField);
            System.out.println("📋 使用页数字段: " + pageCountField);
        } else {
            System.out.println("🎯 未检测到分件相关字段，使用单件处理模式");
            result = new ImageGroupingResult(ImageGroupingType.SINGLE_PIECE);
        }

        result.setFieldMapping(fieldMapping);
        return result;
    }

    /**
     * 检查字段映射中是否包含指定的字段值
     */
    private boolean containsFieldValue(Map<String, String> fieldMapping, String targetFieldName) {
        return fieldMapping.values().stream()
                .anyMatch(fieldName -> fieldName != null && fieldName.contains(targetFieldName));
    }

    /**
     * 根据字段值反向查找字段ID
     */
    private String getFieldIdByValue(Map<String, String> fieldMapping, String targetFieldName) {
        return fieldMapping.entrySet().stream()
                .filter(entry -> entry.getValue() != null && entry.getValue().contains(targetFieldName))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    /**
     * 根据多个字段值按优先级查找字段ID
     * 按传入参数的顺序进行优先级匹配
     */
    private String getFieldIdByMultipleValues(Map<String, String> fieldMapping, String... targetFieldNames) {
        for (String targetFieldName : targetFieldNames) {
            String fieldId = getFieldIdByValue(fieldMapping, targetFieldName);
            if (fieldId != null) {
                System.out.println(String.format("🔍 找到字段匹配: %s -> %s", targetFieldName, fieldId));
                return fieldId;
            }
        }
        System.out.println("❌ 未找到匹配的字段: " + String.join(", ", targetFieldNames));
        return null;
    }

    /**
     * 在内存中处理图像分件（不存数据库）
     */
    private List<ProjectTaskImageDataDTO> processImageGroupingInMemory(
            List<ProjectTaskFormDataDTO> formDataList,
            List<ProjectTaskImageDataDTO> imageDataList,
            ProjectTaskConfigDTO taskConfigDTO,
            ImageGroupingResult groupingResult) {

        System.out.println("🚀🚀🚀 === 开始内存图像分件处理 === 🚀🚀🚀");
        System.out.println("📋 分件类型: " + groupingResult.getGroupingType());
        System.out.println("📋 Excel条目数量: " + formDataList.size());
        System.out.println("📋 图像数据数量: " + imageDataList.size());

        try {
            // 🔍 根据分件类型处理
            switch (groupingResult.getGroupingType()) {
                case START_END_PAGE:
                    return processStartEndPageGrouping(formDataList, imageDataList, groupingResult);
                case START_PAGE_COUNT:
                    return processStartPageCountGrouping(formDataList, imageDataList, groupingResult);
                case PAGE_RANGE:
                    return processPageRangeGrouping(formDataList, imageDataList, groupingResult);
                default:
                    System.out.println("🔍 单件处理模式，返回原始图像数据");
                    return imageDataList;
            }
        } catch (Exception e) {
            System.err.println("❌ 图像分件处理异常: " + e.getMessage());
            e.printStackTrace();
            System.out.println("🔄 回退到原始图像数据");
            return imageDataList;
        }
    }

    /**
     * 处理首页号+尾页号分件模式
     */
    private List<ProjectTaskImageDataDTO> processStartEndPageGrouping(
            List<ProjectTaskFormDataDTO> formDataList,
            List<ProjectTaskImageDataDTO> imageDataList,
            ImageGroupingResult groupingResult) {

        System.out.println("📄 处理首页号+尾页号分件模式");

        String startPageField = groupingResult.getStartPageField();
        String endPageField = groupingResult.getEndPageField();

        System.out.println("📋 首页号字段ID: " + startPageField);
        System.out.println("📋 尾页号字段ID: " + endPageField);

        if (startPageField == null || endPageField == null) {
            System.err.println("❌ 首页号或尾页号字段ID为空，无法进行分件处理");
            return imageDataList;
        }

        List<ProjectTaskImageDataDTO> result = new ArrayList<>();

        // 🔍 遍历Excel数据，为每个条目创建对应的图像组
        for (ProjectTaskFormDataDTO formData : formDataList) {
            try {
                // 🔍 解析Excel中的首页号和尾页号
                String startPageStr = getFieldValueFromFormData(formData, startPageField);
                String endPageStr = getFieldValueFromFormData(formData, endPageField);

                System.out.println(String.format("📋 Excel行%d: 首页号=%s, 尾页号=%s",
                    formData.getRowNum(), startPageStr, endPageStr));

                if (startPageStr != null && endPageStr != null) {
                    try {
                        int startPage = Integer.parseInt(startPageStr.trim());
                        int endPage = Integer.parseInt(endPageStr.trim());

                        if (startPage > 0 && endPage >= startPage) {
                            // 🔍 为这个页号范围创建图像数据组
                            ProjectTaskImageDataDTO imageGroup = createImageGroupForPageRange(
                                formData, imageDataList, startPage, endPage);

                            if (imageGroup != null) {
                                result.add(imageGroup);
                                System.out.println(String.format("✅ 创建图像组: 行%d, 页号%d-%d, 共%d页",
                                    formData.getRowNum(), startPage, endPage, (endPage - startPage + 1)));
                            }
                        } else {
                            System.err.println(String.format("❌ Excel行%d页号范围无效: %d-%d",
                                formData.getRowNum(), startPage, endPage));
                        }
                    } catch (NumberFormatException e) {
                        System.err.println(String.format("❌ Excel行%d页号解析失败: 首页号=%s, 尾页号=%s",
                            formData.getRowNum(), startPageStr, endPageStr));
                    }
                } else {
                    System.err.println(String.format("❌ Excel行%d页号字段为空: 首页号=%s, 尾页号=%s",
                        formData.getRowNum(), startPageStr, endPageStr));
                }
            } catch (Exception e) {
                System.err.println(String.format("❌ Excel行%d处理异常: %s", formData.getRowNum(), e.getMessage()));
            }
        }

        System.out.println(String.format("📊 首页号+尾页号分件完成: 输入%d个Excel条目，输出%d个图像组",
            formDataList.size(), result.size()));

        return result.isEmpty() ? imageDataList : result;
    }

    /**
     * 处理首页号+页数分件模式
     */
    private List<ProjectTaskImageDataDTO> processStartPageCountGrouping(
            List<ProjectTaskFormDataDTO> formDataList,
            List<ProjectTaskImageDataDTO> imageDataList,
            ImageGroupingResult groupingResult) {

        System.out.println("📄 处理首页号+页数分件模式");

        // 🔍 这里实现具体的分件逻辑
        // 暂时返回原始数据，后续可以根据实际需求完善
        System.out.println("⚠️ 首页号+页数分件逻辑待完善，暂时返回原始数据");
        return imageDataList;
    }

    /**
     * 处理起止页号分件模式
     */
    private List<ProjectTaskImageDataDTO> processPageRangeGrouping(
            List<ProjectTaskFormDataDTO> formDataList,
            List<ProjectTaskImageDataDTO> imageDataList,
            ImageGroupingResult groupingResult) {

        System.out.println("📄 处理起止页号分件模式");

        // 🔍 这里实现具体的分件逻辑
        // 暂时返回原始数据，后续可以根据实际需求完善
        System.out.println("⚠️ 起止页号分件逻辑待完善，暂时返回原始数据");
        return imageDataList;
    }

    /**
     * 根据起始页码和终止页码提取图像
     */
    private List<String> extractByStartEndPage(Map<String, Object> taskData,
                                             Map<String, String> ruleKeyFieldMap,
                                             Set<String> availableImages) {
        List<String> result = new ArrayList<>();

        try {
            String startPageField = ruleKeyFieldMap.get("起始页码");
            String endPageField = ruleKeyFieldMap.get("终止页码");

            if (startPageField != null && endPageField != null) {
                Object startPageObj = taskData.get(startPageField);
                Object endPageObj = taskData.get(endPageField);

                if (startPageObj != null && endPageObj != null) {
                    int startPage = Integer.parseInt(startPageObj.toString());
                    int endPage = Integer.parseInt(endPageObj.toString());

                    for (int page = startPage; page <= endPage; page++) {
                        String imageName = String.format("page_%d.jpg", page);
                        if (availableImages.contains(imageName)) {
                            result.add(imageName);
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("根据起始终止页码提取图像失败: " + e.getMessage());
        }

        return result;
    }

    /**
     * 根据起始页码和页数提取图像
     */
    private List<String> extractByStartPageCount(Map<String, Object> taskData,
                                               Map<String, String> ruleKeyFieldMap,
                                               Set<String> availableImages) {
        List<String> result = new ArrayList<>();

        try {
            String startPageField = ruleKeyFieldMap.get("起始页码");
            String pageCountField = ruleKeyFieldMap.get("页数");

            if (startPageField != null && pageCountField != null) {
                Object startPageObj = taskData.get(startPageField);
                Object pageCountObj = taskData.get(pageCountField);

                if (startPageObj != null && pageCountObj != null) {
                    int startPage = Integer.parseInt(startPageObj.toString());
                    int pageCount = Integer.parseInt(pageCountObj.toString());
                    int endPage = startPage + pageCount - 1;

                    for (int page = startPage; page <= endPage; page++) {
                        String imageName = String.format("page_%d.jpg", page);
                        if (availableImages.contains(imageName)) {
                            result.add(imageName);
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("根据起始页码和页数提取图像失败: " + e.getMessage());
        }

        return result;
    }

    /**
     * 根据页码范围提取图像
     */
    private List<String> extractByPageRange(Map<String, Object> taskData,
                                          Map<String, String> ruleKeyFieldMap,
                                          Set<String> availableImages) {
        List<String> result = new ArrayList<>();

        try {
            String pageRangeField = ruleKeyFieldMap.get("页码范围");

            if (pageRangeField != null) {
                Object pageRangeObj = taskData.get(pageRangeField);

                if (pageRangeObj != null) {
                    String pageRange = pageRangeObj.toString();
                    // 解析页码范围，如 "1-5" 或 "1,3,5-7"
                    String[] ranges = pageRange.split(",");

                    for (String range : ranges) {
                        range = range.trim();
                        if (range.contains("-")) {
                            String[] parts = range.split("-");
                            if (parts.length == 2) {
                                int start = Integer.parseInt(parts[0].trim());
                                int end = Integer.parseInt(parts[1].trim());
                                for (int page = start; page <= end; page++) {
                                    String imageName = String.format("page_%d.jpg", page);
                                    if (availableImages.contains(imageName)) {
                                        result.add(imageName);
                                    }
                                }
                            }
                        } else {
                            int page = Integer.parseInt(range);
                            String imageName = String.format("page_%d.jpg", page);
                            if (availableImages.contains(imageName)) {
                                result.add(imageName);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("根据页码范围提取图像失败: " + e.getMessage());
        }

        return result;
    }

    /**
     * 获取档案要素到Excel字段的映射
     */
    private Map<String, String> getElementToExcelFieldMapping(PublicRuleDTO ruleDTO, ProjectTaskConfigDTO taskConfigDTO, List<String> selectedElements) {
        Map<String, String> elementToExcelFieldMap = new HashMap<>();

        try {
            // 🔍 获取规则模板配置的字段映射
            Map<String, String> ruleKeyFieldMap = taskConfigDTO.buildRuleKeyFieldMap();

            // 🔍 要素到默认中文名称的映射
            Map<String, String> elementToChineseMap = new HashMap<>();
            elementToChineseMap.put("title", "题名");
            elementToChineseMap.put("responsible_party", "责任者");
            elementToChineseMap.put("document_number", "文号");
            elementToChineseMap.put("issue_date", "成文日期");

            // 🔍 只为选中的要素构建映射
            for (String element : selectedElements) {
                String defaultChineseName = elementToChineseMap.get(element);
                if (defaultChineseName != null) {
                    String excelFieldName = ruleKeyFieldMap.getOrDefault(defaultChineseName, defaultChineseName);
                    elementToExcelFieldMap.put(element, excelFieldName);
                }
            }

            System.out.println("🔍 字段映射配置（基于选中要素）:");
            for (Map.Entry<String, String> entry : elementToExcelFieldMap.entrySet()) {
                System.out.println(String.format("  %s -> %s", entry.getKey(), entry.getValue()));
            }

        } catch (Exception e) {
            System.err.println("获取字段映射失败: " + e.getMessage());
            e.printStackTrace();

            // 🔍 使用默认映射（只为选中的要素）
            Map<String, String> elementToChineseMap = new HashMap<>();
            elementToChineseMap.put("title", "题名");
            elementToChineseMap.put("responsible_party", "责任者");
            elementToChineseMap.put("document_number", "文号");
            elementToChineseMap.put("issue_date", "成文日期");

            for (String element : selectedElements) {
                String defaultChineseName = elementToChineseMap.get(element);
                if (defaultChineseName != null) {
                    elementToExcelFieldMap.put(element, defaultChineseName);
                }
            }
        }

        return elementToExcelFieldMap;
    }

    /**
     * 调用Python档案要素检查服务
     */
    private ArchiveElementsCheckResult callPythonArchiveService(String taskId,
                                                              List<Map<String, Object>> excelData,
                                                              List<ProjectTaskImageDataDTO> imageDataList,
                                                              List<String> selectedElements,
                                                              double confidenceThreshold,
                                                              double similarityThreshold,
                                                              boolean enableStampProcessing,
                                                              double stampConfidenceThreshold,
                                                              boolean enablePreprocessing) {
        try {
            System.out.println("🔍 使用的参数配置:");
            System.out.println("  confidence_threshold: " + confidenceThreshold);
            System.out.println("  similarity_threshold: " + similarityThreshold);
            System.out.println("  enable_stamp_processing: " + enableStampProcessing);
            System.out.println("  stamp_confidence_threshold: " + stampConfidenceThreshold);
            System.out.println("  enable_preprocessing: " + enablePreprocessing);
            System.out.println("  selectedElements: " + selectedElements);

            // 🌐 智能提取AI服务地址
            String serviceUrl = "http://localhost:8080/extract/archive/batch_compare";

            // 🔍 准备分件数据 - 转换为Python端需要的格式
            List<Map<String, Object>> imageDataForPython = new ArrayList<>();
            for (ProjectTaskImageDataDTO imageData : imageDataList) {
                Map<String, Object> imageInfo = new HashMap<>();
                imageInfo.put("path", imageData.getImageFilePath());
                imageInfo.put("filename", imageData.getImageFilePath() != null ?
                    new File(imageData.getImageFilePath()).getName() : "unknown");
                imageInfo.put("dataKey", imageData.getDataKey());
                imageInfo.put("partNumber", imageData.getPartNumber());
                imageInfo.put("imageNames", imageData.getImageNames());
                imageInfo.put("imageCount", imageData.getImageCount());
                imageDataForPython.add(imageInfo);

                System.out.println(String.format("🐍 传递给Python的图像数据: dataKey=%s, partNumber=%s, path=%s, imageNames=%s",
                    imageData.getDataKey(), imageData.getPartNumber(), imageData.getImageFilePath(), imageData.getImageNames()));
            }

            // 📝 准备请求参数
            MultiValueMap<String, Object> params = new LinkedMultiValueMap<>();
            params.add("task_id", taskId);
            params.add("excel_data", JsonHelper.toJson(excelData));
            params.add("image_data", JsonHelper.toJson(imageDataForPython)); // 🔍 传递完整的分件数据
            params.add("elements", JsonHelper.toJson(selectedElements));
            params.add("confidence_threshold", confidenceThreshold);
            params.add("similarity_threshold", similarityThreshold);
            params.add("enable_stamp_processing", enableStampProcessing);
            params.add("stamp_confidence_threshold", stampConfidenceThreshold);
            params.add("enable_preprocessing", enablePreprocessing);

            System.out.println("🐍🐍🐍 === 调用Python服务参数 === 🐍🐍🐍");
            System.out.println("task_id: " + taskId);
            System.out.println("excel_data条目数: " + excelData.size());
            System.out.println("image_data条目数: " + imageDataForPython.size());
            System.out.println("🐍🐍🐍 === 参数准备完成 === 🐍🐍🐍");

            // 🚀 发送HTTP请求
            RestTemplate restTemplate = new RestTemplate();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(params, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(serviceUrl, requestEntity, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                // 📊 解析响应结果
                String responseBody = response.getBody();

                // 🔍 添加调试日志 - 查看Python服务响应
                System.out.println("🐍🐍🐍 === Python档案要素检查服务完整响应 === 🐍🐍🐍");
                System.out.println("响应状态码: " + response.getStatusCode());
                System.out.println("响应体长度: " + (responseBody != null ? responseBody.length() : 0));
                System.out.println("响应内容: " + responseBody);

                Map<String, Object> resultMap = JsonHelper.json2map(responseBody);

                // 🔍 详细解析响应结构
                System.out.println("📊📊📊 === 解析响应结构 === 📊📊📊");
                System.out.println("success: " + resultMap.get("success"));
                System.out.println("task_id: " + resultMap.get("task_id"));
                System.out.println("error: " + resultMap.get("error"));

                Object comparisonResult = resultMap.get("comparison_result");
                System.out.println("comparison_result类型: " + (comparisonResult != null ? comparisonResult.getClass().getSimpleName() : "null"));

                if (comparisonResult instanceof Map) {
                    Map<String, Object> compMap = (Map<String, Object>) comparisonResult;
                    System.out.println("comparison_result条目数: " + compMap.size());
                    System.out.println("comparison_result键列表: " + compMap.keySet());

                    // 🔍 详细打印每个dataKey的比对结果
                    for (Map.Entry<String, Object> entry : compMap.entrySet()) {
                        String dataKey = entry.getKey();
                        Object rowResult = entry.getValue();
                        System.out.println(String.format("  📋 dataKey: %s -> 结果类型: %s",
                            dataKey, rowResult != null ? rowResult.getClass().getSimpleName() : "null"));

                        if (rowResult instanceof Map) {
                            Map<String, Object> rowData = (Map<String, Object>) rowResult;
                            System.out.println(String.format("    📄 %s包含字段: %s", dataKey, rowData.keySet()));

                            // 打印每个要素的详细信息
                            for (Map.Entry<String, Object> elementEntry : rowData.entrySet()) {
                                String elementName = elementEntry.getKey();
                                Object elementData = elementEntry.getValue();
                                System.out.println(String.format("      🔍 %s.%s: %s",
                                    dataKey, elementName, elementData));
                            }
                        }
                    }
                }

                ArchiveElementsCheckResult result = new ArchiveElementsCheckResult();
                result.setSuccess((Boolean) resultMap.get("success"));
                result.setTaskId((String) resultMap.get("task_id"));
                result.setComparisonResult(resultMap.get("comparison_result"));
                result.setError((String) resultMap.get("error"));

                System.out.println("✅✅✅ === Java端解析完成 === ✅✅✅");
                System.out.println("最终结果success: " + result.isSuccess());
                System.out.println("最终结果task_id: " + result.getTaskId());
                System.out.println("最终结果error: " + result.getError());

                return result;
            } else {
                System.err.println("Python服务调用失败，状态码: " + response.getStatusCode());
                return null;
            }

        } catch (Exception e) {
            System.err.println("调用Python档案要素检查服务失败: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 将档案要素检查结果转换为TaskErrorResultDO列表
     */
    private List<TaskErrorResultDO> convertToTaskErrorResults(ArchiveElementsCheckResult result,
                                                            ProjectTaskConfigDTO taskConfigDTO,
                                                            ProjectTaskInfoDTO taskInfoDTO,
                                                            PublicRuleDTO ruleDTO) {
        List<TaskErrorResultDO> errorResults = new ArrayList<>();

        System.out.println("🔍🔍🔍 === convertToTaskErrorResults 开始 === 🔍🔍🔍");
        System.out.println(String.format("taskId: %s", taskInfoDTO.getId()));
        System.out.println(String.format("ruleName: %s", ruleDTO.getRuleAliasName()));

        try {
            if (result == null || !result.isSuccess() || result.getComparisonResult() == null) {
                System.out.println("⚠️ 结果为空或失败，返回空列表");
                return errorResults;
            }

            String taskId = taskInfoDTO.getId();

            // 🔍 预先获取formData映射，避免重复查询
            List<ProjectTaskFormDataDTO> formDataList = projectTaskFormDataService.findByTaskId(taskId);
            Map<String, Integer> dataKeyToRowNumMap = new HashMap<>();
            for (ProjectTaskFormDataDTO formData : formDataList) {
                if (formData.getDataKey() != null && formData.getRowNum() != null) {
                    dataKeyToRowNumMap.put(formData.getDataKey(), formData.getRowNum());
                }
            }

            // 📊 解析比对结果
            Object comparisonResult = result.getComparisonResult();
            if (comparisonResult instanceof Map) {
                Map<String, Object> resultMap = (Map<String, Object>) comparisonResult;

                // 处理每个Excel行的比对结果
                for (Map.Entry<String, Object> entry : resultMap.entrySet()) {
                    String resultKey = entry.getKey(); // 现在是 dataKey_rowNum 格式
                    Object rowResult = entry.getValue();

                    if (rowResult instanceof Map) {
                        Map<String, Object> rowData = (Map<String, Object>) rowResult;

                        // 🔍 解析新的键格式：dataKey_rowNum
                        String dataKey;
                        Integer rowNum;

                        if (resultKey.contains("_")) {
                            // 新格式：dataKey_rowNum
                            String[] parts = resultKey.split("_");
                            if (parts.length >= 2) {
                                dataKey = parts[0]; // 可能包含多个部分，如 123-023-00001
                                try {
                                    rowNum = Integer.parseInt(parts[parts.length - 1]); // 最后一部分是rowNum
                                    // 重新构建dataKey（除了最后的rowNum部分）
                                    if (parts.length > 2) {
                                        StringBuilder sb = new StringBuilder();
                                        for (int i = 0; i < parts.length - 1; i++) {
                                            if (i > 0) sb.append("_");
                                            sb.append(parts[i]);
                                        }
                                        dataKey = sb.toString();
                                    }
                                } catch (NumberFormatException e) {
                                    // 如果最后一部分不是数字，则整个作为dataKey
                                    dataKey = resultKey;
                                    rowNum = dataKeyToRowNumMap.get(dataKey);
                                }
                            } else {
                                dataKey = resultKey;
                                rowNum = dataKeyToRowNumMap.get(dataKey);
                            }
                        } else {
                            // 旧格式：纯dataKey
                            dataKey = resultKey;
                            rowNum = dataKeyToRowNumMap.get(dataKey);
                        }

                        System.out.println(String.format("🔍 解析结果键: %s -> dataKey: %s, rowNum: %d",
                            resultKey, dataKey, rowNum != null ? rowNum : -1));

                        // 检查每个档案要素的错误
                        processArchiveElementErrors(errorResults, taskId, taskConfigDTO.getId(),
                                                   dataKey, rowData, ruleDTO, dataKeyToRowNumMap, rowNum);
                    }
                }
            }

        } catch (Exception e) {
            System.err.println("转换档案要素检查错误结果失败: " + e.getMessage());
            e.printStackTrace();
        }

        // 🔍 最终统计
        System.out.println("🔍🔍🔍 === convertToTaskErrorResults 结束 === 🔍🔍🔍");
        System.out.println(String.format("总错误记录数: %d", errorResults.size()));

        // 🔍 简单统计各字段错误数
        Map<String, Integer> fieldCounts = new HashMap<>();
        Map<String, Integer> dataKeyCounts = new HashMap<>();

        for (TaskErrorResultDO error : errorResults) {
            // 统计字段错误
            String fieldName = error.getFieldName();
            fieldCounts.put(fieldName, fieldCounts.getOrDefault(fieldName, 0) + 1);

            // 统计dataKey错误
            String dataKey = error.getDataKey();
            dataKeyCounts.put(dataKey, dataKeyCounts.getOrDefault(dataKey, 0) + 1);
        }

        System.out.println("按字段名分组统计: " + fieldCounts);
        System.out.println("按dataKey分组统计: " + dataKeyCounts);

        return errorResults;
    }

    /**
     * 处理单行档案要素错误
     */
    private void processArchiveElementErrors(List<TaskErrorResultDO> errorResults,
                                           String taskId, String taskConfigId,
                                           String dataKey, Map<String, Object> rowData,
                                           PublicRuleDTO archiveElementsRule,
                                           Map<String, Integer> dataKeyToRowNumMap,
                                           Integer providedRowNum) {

        // 🔍 获取档案要素到Excel字段的映射（这个映射已经在executeArchiveElementsCheck中计算过了）
        // 但这里我们需要反向映射：从英文字段名到Excel列名
        Map<String, String> elementToExcelFieldMap = new HashMap<>();
        ProjectTaskConfigDTO taskConfigDTO = null; // 🔍 在更大的作用域中定义

        try {
            // 🔍 获取任务配置
            taskConfigDTO = projectTaskConfigService.findById(taskConfigId);

            // 🔍 解析规则模板配置获取字段映射
            String ruleValue = archiveElementsRule.getRuleValue();
            if (StringHelper.isNotEmpty(ruleValue)) {
                Map<String, Object> ruleConfig = JsonHelper.json2map(ruleValue);
                Map<String, Object> fieldMapping = (Map<String, Object>) ruleConfig.get("fieldMapping");

                if (fieldMapping != null) {
                    // 🔍 获取字段库映射 (字段ID -> Excel列名)
                    Map<String, String> fieldLibraryMap = taskConfigDTO.buildRuleKeyFieldMap();

                    // 🔍 构建档案要素到Excel字段的映射
                    for (Map.Entry<String, Object> entry : fieldMapping.entrySet()) {
                        String elementKey = entry.getKey();        // 如: "title"
                        String fieldId = (String) entry.getValue(); // 如: "field_id_123"

                        // 从字段库映射中获取Excel列名
                        String excelFieldName = fieldLibraryMap.get(fieldId);
                        if (StringHelper.isNotEmpty(excelFieldName)) {
                            elementToExcelFieldMap.put(elementKey, excelFieldName);
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("获取字段映射失败: " + e.getMessage());
        }

        // 🔍 如果没有配置，使用默认映射（使用实际的Excel列名）
        if (elementToExcelFieldMap.isEmpty()) {
            elementToExcelFieldMap.put("title", "题名");        // ✅ 使用实际Excel列名
            elementToExcelFieldMap.put("responsible_party", "责任者"); // ✅ 正确
            elementToExcelFieldMap.put("document_number", "文号");    // ✅ 正确
            elementToExcelFieldMap.put("issue_date", "成文日期");     // ✅ 使用实际Excel列名
        }

        // 🔍 获取Excel行号 - 优先使用传入的rowNum，回退到映射查找
        Integer errorRow = providedRowNum;
        if (errorRow == null) {
            errorRow = dataKeyToRowNumMap.get(dataKey);
            if (errorRow == null) {
                System.err.println("未找到dataKey对应的rowNum: " + dataKey + "，使用默认行号2");
                errorRow = 2; // 默认为第2行（跳过标题行）
            }
        }

        System.out.println(String.format("🔍 确定Excel行号: dataKey=%s, providedRowNum=%s, finalRowNum=%d",
            dataKey, providedRowNum, errorRow));

        // 🔍 添加调试日志 - 查看rowData的完整内容
        System.out.println(String.format("rowData内容 - dataKey: %s, 数据: %s", dataKey, rowData));

        // 检查每个要素的错误
        for (Map.Entry<String, String> element : elementToExcelFieldMap.entrySet()) {
            String elementKey = element.getKey();
            String excelFieldName = element.getValue();

            Object elementResult = rowData.get(elementKey);
            System.out.println(String.format("查找要素 - elementKey: %s, excelFieldName: %s, elementResult: %s",
                elementKey, excelFieldName, elementResult));

            if (elementResult instanceof Map) {
                Map<String, Object> elementData = (Map<String, Object>) elementResult;

                Boolean hasError = (Boolean) elementData.get("has_error");
                String excelValue = (String) elementData.get("excel_value");
                String extractedValue = (String) elementData.get("extracted_value");
                Double similarity = (Double) elementData.get("similarity");

                // 🔍 添加调试日志
                System.out.println(String.format("档案要素检查 - dataKey: %s, 要素: %s, Excel值: [%s], 提取值: [%s], 相似度: %.2f, 有错误: %s",
                    dataKey, excelFieldName, excelValue, extractedValue, similarity != null ? similarity : 0.0, hasError));

                if (hasError != null && hasError) {
                    // 🚨 创建错误记录 - 直接使用Excel字段名作为fieldName，因为Excel导出时期望的就是Excel列名
                    // 从日志分析：keyValueMaps = {卷内题名=卷内题名}，所以chineseFieldName就是Excel列名
                    TaskErrorResultDO errorResult = TaskErrorResultDO.builder()
                        .taskId(taskId)
                        .taskConfigId(taskConfigId)
                        .dataKey(dataKey)
                        .fieldName(excelFieldName) // 🔍 直接使用Excel字段名，这样Excel导出时能正确匹配
                        .ruleName(archiveElementsRule.getRuleAliasName()) // 🔍 使用规则模板中定义的规则名称
                        .ruleType("archiveElements")
                        .errorType(ErrorResultType.RULE.getNum()) // 使用RULE类型
                        .errorRow(errorRow) // 🔍 设置Excel行号
                        .aiCheck(1) // 标记为AI检查
                        .build();

                    System.out.println(String.format("🔍 创建档案要素错误记录 - taskId: %s, fieldName: %s, ruleName: %s, ruleType: %s, errorType: %d, errorRow: %d",
                        taskId, excelFieldName, archiveElementsRule.getRuleAliasName(), "archiveElements", ErrorResultType.RULE.getNum(), errorRow));

                    String suggestion = (String) elementData.get("suggestion");

                    String errorDescription = String.format(
                        "%s不匹配：Excel值[%s] vs 提取值[%s]，相似度%.2f",
                        excelFieldName, excelValue, extractedValue, similarity != null ? similarity : 0.0
                    );

                    errorResult.setErrorFileValue(errorDescription);
                    errorResult.setErrorCoordinate(suggestion); // 将建议存储在坐标字段中

                    errorResults.add(errorResult);

                    // 🔍 添加调试日志 - 记录每个错误记录的创建
                    System.out.println(String.format("🔍 创建错误记录: dataKey=%s, fieldName=%s, errorRow=%d, ruleName=%s",
                        dataKey, excelFieldName, errorRow, archiveElementsRule.getRuleAliasName()));
                }
            }
        }

        // 🔍 在方法结束时打印该dataKey的错误统计
        System.out.println(String.format("🔍 dataKey [%s] 处理完成，创建错误记录数: %d", dataKey,
            (int) errorResults.stream().filter(e -> dataKey.equals(e.getDataKey())).count()));
    }

    /**
     * 档案要素检查结果数据类
     */
    @Data
    public static class ArchiveElementsCheckResult {
        private boolean success;
        private String taskId;
        private Object comparisonResult;
        private String error;
    }

    /**
     * 图像分组检查结果
     */
    public static class ImageGroupingResult {
        private ImageGroupingType groupingType;
        private Map<String, String> fieldMapping;
        private String startPageField;
        private String endPageField;
        private String pageCountField;
        private String pageRangeField;

        public ImageGroupingResult(ImageGroupingType groupingType) {
            this.groupingType = groupingType;
            this.fieldMapping = new HashMap<>();
        }

        public boolean needsGrouping() {
            return groupingType != ImageGroupingType.SINGLE_PIECE;
        }

        // Getters and setters
        public ImageGroupingType getGroupingType() { return groupingType; }
        public void setGroupingType(ImageGroupingType groupingType) { this.groupingType = groupingType; }
        public Map<String, String> getFieldMapping() { return fieldMapping; }
        public void setFieldMapping(Map<String, String> fieldMapping) { this.fieldMapping = fieldMapping; }
        public String getStartPageField() { return startPageField; }
        public void setStartPageField(String startPageField) { this.startPageField = startPageField; }
        public String getEndPageField() { return endPageField; }
        public void setEndPageField(String endPageField) { this.endPageField = endPageField; }
        public String getPageCountField() { return pageCountField; }
        public void setPageCountField(String pageCountField) { this.pageCountField = pageCountField; }
        public String getPageRangeField() { return pageRangeField; }
        public void setPageRangeField(String pageRangeField) { this.pageRangeField = pageRangeField; }
    }

    /**
     * 从Excel表单数据中获取指定字段的值
     */
    private String getFieldValueFromFormData(ProjectTaskFormDataDTO formData, String fieldId) {
        try {
            if (formData.getTaskJson() != null) {
                // 🔍 解析JSON数据
                Map<String, Object> taskJson = JsonHelper.fromJson(formData.getTaskJson(), Map.class);
                if (taskJson != null && taskJson.containsKey(fieldId)) {
                    Object value = taskJson.get(fieldId);
                    return value != null ? value.toString() : null;
                }
            }
        } catch (Exception e) {
            System.err.println(String.format("❌ 解析Excel行%d字段%s失败: %s",
                formData.getRowNum(), fieldId, e.getMessage()));
        }
        return null;
    }

    /**
     * 为指定页号范围创建图像数据组
     */
    private ProjectTaskImageDataDTO createImageGroupForPageRange(
            ProjectTaskFormDataDTO formData,
            List<ProjectTaskImageDataDTO> imageDataList,
            int startPage, int endPage) {

        try {
            // 🔍 根据dataKey查找对应的原始图像数据
            ProjectTaskImageDataDTO matchingImage = null;
            for (ProjectTaskImageDataDTO imageData : imageDataList) {
                if (formData.getDataKey().equals(imageData.getDataKey())) {
                    matchingImage = imageData;
                    break;
                }
            }

            // 🔍 如果没找到匹配的图像，使用第一个作为模板
            ProjectTaskImageDataDTO template = matchingImage != null ? matchingImage : imageDataList.get(0);

            if (template != null) {
                ProjectTaskImageDataDTO imageGroup = new ProjectTaskImageDataDTO();
                imageGroup.setTaskId(template.getTaskId());
                imageGroup.setTaskConfigId(template.getTaskConfigId());
                imageGroup.setDataKey(formData.getDataKey());
                imageGroup.setPartNumber(formData.getPartNumber());

                // 🔍 使用匹配的图像路径，如果没有匹配则构建路径
                String imagePath;
                if (matchingImage != null) {
                    imagePath = matchingImage.getImageFilePath();
                    System.out.println(String.format("✅ 找到匹配图像: dataKey=%s -> path=%s",
                        formData.getDataKey(), imagePath));
                } else {
                    // 🔍 根据dataKey构建图像路径
                    String basePath = template.getImageFilePath();
                    // 提取基础路径并替换最后的dataKey部分
                    String[] pathParts = formData.getDataKey().split("-");
                    if (pathParts.length >= 3) {
                        String newPath = basePath.replaceAll("\\\\[^\\\\]+\\\\[^\\\\]+\\\\[^\\\\]+$",
                            "\\\\" + pathParts[0] + "\\\\" + pathParts[1] + "\\\\" + pathParts[2]);
                        imagePath = newPath;
                        System.out.println(String.format("🔧 构建图像路径: dataKey=%s -> path=%s",
                            formData.getDataKey(), imagePath));
                    } else {
                        imagePath = template.getImageFilePath();
                        System.out.println(String.format("⚠️ 无法构建路径，使用模板: dataKey=%s -> path=%s",
                            formData.getDataKey(), imagePath));
                    }
                }

                imageGroup.setImageFilePath(imagePath);
                imageGroup.setImageCount(endPage - startPage + 1);
                imageGroup.setCreateTime(template.getCreateTime());

                // 🔍 从真实的图像数据中获取页号范围内的图像名称列表
                List<String> imageNames = new ArrayList<>();

                // 🔍 获取当前dataKey对应的图像数据的图像名称列表
                List<String> currentImageNames = null;

                if (matchingImage != null) {
                    // 如果找到了匹配的图像数据，直接使用其图像名称列表
                    currentImageNames = matchingImage.getImageNames();
                    System.out.println(String.format("✅ 使用匹配图像的名称列表: dataKey=%s", matchingImage.getDataKey()));
                } else {
                    // 🔍 如果没有找到匹配的图像，需要从数据库重新获取当前dataKey的图像数据
                    System.out.println(String.format("🔍 未找到匹配图像，尝试从数据库获取: dataKey=%s", formData.getDataKey()));

                    // 从imageDataList中查找所有匹配当前dataKey的图像数据
                    for (ProjectTaskImageDataDTO imageData : imageDataList) {
                        if (formData.getDataKey().equals(imageData.getDataKey())) {
                            if (imageData.getImageNames() != null && !imageData.getImageNames().isEmpty()) {
                                currentImageNames = imageData.getImageNames();
                                System.out.println(String.format("🔍 从数据库找到匹配图像: dataKey=%s", imageData.getDataKey()));
                                break;
                            }
                        }
                    }

                    // 如果还是没找到，尝试通过文件系统扫描获取
                    if (currentImageNames == null || currentImageNames.isEmpty()) {
                        System.out.println(String.format("🔍 数据库中未找到图像名称，尝试扫描文件系统: path=%s", imagePath));
                        currentImageNames = scanImageFilesFromPath(imagePath);
                    }
                }

                if (currentImageNames != null && !currentImageNames.isEmpty()) {
                    // 🔍 根据页号范围筛选真实的图像名称
                    System.out.println(String.format("🔍 当前图像名称列表: %s", currentImageNames));
                    System.out.println(String.format("🔍 需要的页号范围: %d-%d", startPage, endPage));

                    // 计算页号范围对应的索引范围
                    int startIndex = startPage - 1; // 页号从1开始，索引从0开始
                    int endIndex = endPage - 1;

                    for (int i = startIndex; i <= endIndex && i < currentImageNames.size(); i++) {
                        if (i >= 0) {
                            imageNames.add(currentImageNames.get(i));
                            System.out.println(String.format("🔍 添加图像: 页号%d -> %s", i + 1, currentImageNames.get(i)));
                        }
                    }

                    System.out.println(String.format("🔍 筛选后的图像名称列表: %s", imageNames));
                } else {
                    // 🔍 如果没有找到图像名称，则构造默认名称（兼容旧逻辑）
                    System.out.println("⚠️ 未找到图像名称列表，使用默认命名规则");
                    for (int page = startPage; page <= endPage; page++) {
                        imageNames.add(String.format("page_%d.jpg", page));
                    }
                }

                imageGroup.setImageNames(imageNames);

                System.out.println(String.format("📋 创建图像组: dataKey=%s, 页号%d-%d, 图像数量=%d, 路径=%s",
                    formData.getDataKey(), startPage, endPage, imageGroup.getImageCount(), imagePath));

                return imageGroup;
            }
        } catch (Exception e) {
            System.err.println(String.format("❌ 创建图像组失败: dataKey=%s, 页号%d-%d, 错误=%s",
                formData.getDataKey(), startPage, endPage, e.getMessage()));
            e.printStackTrace();
        }

        return null;
    }

    /**
     * 从指定路径扫描图像文件名称列表
     */
    private List<String> scanImageFilesFromPath(String imagePath) {
        List<String> imageNames = new ArrayList<>();

        try {
            File imageDir = new File(imagePath);
            if (imageDir.exists() && imageDir.isDirectory()) {
                File[] files = imageDir.listFiles((dir, name) -> {
                    String lowerName = name.toLowerCase();
                    return lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") ||
                           lowerName.endsWith(".png") || lowerName.endsWith(".bmp") ||
                           lowerName.endsWith(".tiff") || lowerName.endsWith(".tif");
                });

                if (files != null) {
                    // 按文件名排序
                    Arrays.sort(files, (f1, f2) -> f1.getName().compareTo(f2.getName()));

                    for (File file : files) {
                        imageNames.add(file.getName());
                    }

                    System.out.println(String.format("🔍 文件系统扫描结果: 路径=%s, 图像数量=%d", imagePath, imageNames.size()));
                    System.out.println(String.format("🔍 扫描到的图像文件: %s", imageNames));
                }
            } else {
                System.out.println(String.format("⚠️ 图像路径不存在或不是目录: %s", imagePath));
            }
        } catch (Exception e) {
            System.err.println(String.format("❌ 扫描图像文件失败: path=%s, error=%s", imagePath, e.getMessage()));
        }

        return imageNames;
    }
}
