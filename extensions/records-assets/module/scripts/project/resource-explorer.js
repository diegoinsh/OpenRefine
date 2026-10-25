/*
 * Resource Explorer - Main Module
 * Manages file path column states and provides resource exploration functionality
 */

var ResourceExplorer = {};

(function() {
  'use strict';

  // Store file path column configurations
  // Key: column name, Value: { enabled: boolean, rootPath: string }
  ResourceExplorer._filePathColumns = {};

  // Local storage key for persisting configuration
  ResourceExplorer._storageKey = 'resource-explorer-columns-';

  // i18n initialized flag
  ResourceExplorer._i18nInitialized = false;

  /**
   * Initialize i18n for records-assets extension
   */
  ResourceExplorer.initI18n = function() {
    if (ResourceExplorer._i18nInitialized) {
      return;
    }

    // Load translations for records-assets module
    if (typeof I18NUtil !== 'undefined' && I18NUtil.init) {
      try {
        I18NUtil.init('records-assets');
        ResourceExplorer._i18nInitialized = true;
        console.log('[ResourceExplorer] i18n initialized for records-assets');
      } catch (e) {
        console.warn('[ResourceExplorer] Failed to initialize i18n:', e);
      }
    }
  };

  /**
   * Initialize ResourceExplorer for current project
   * Called manually or after toggle/set operations
   */
  ResourceExplorer.init = function() {
    // Initialize i18n first
    ResourceExplorer.initI18n();

    if (typeof theProject !== 'undefined' && theProject.id) {
      ResourceExplorer._loadConfig();
    }
  };

  /**
   * Get storage key for current project
   */
  ResourceExplorer._getStorageKey = function() {
    if (typeof theProject !== 'undefined' && theProject.id) {
      return ResourceExplorer._storageKey + theProject.id;
    }
    return null;
  };

  /**
   * Load configuration from localStorage
   */
  ResourceExplorer._loadConfig = function() {
    var key = ResourceExplorer._getStorageKey();
    if (key) {
      try {
        var stored = localStorage.getItem(key);
        if (stored) {
          ResourceExplorer._filePathColumns = JSON.parse(stored);
        }
      } catch (e) {
        console.error('Failed to load ResourceExplorer config:', e);
        ResourceExplorer._filePathColumns = {};
      }
    }
  };
  /**
   * 案卷著录场景下「文件夹路径」是标配列。若该项目尚无任何文件路径列配置，
   * 则把名称含「路径」的列默认标记为文件路径列，使单元格开箱即可点击查看原图，
   * 无需先在列头菜单手动勾选；已有配置的项目不受影响。
   */
  ResourceExplorer._applyDefaultColumns = function() {
    if (Object.keys(ResourceExplorer._filePathColumns).length > 0) {
      return;
    }
    if (typeof theProject === 'undefined' || !theProject.columnModel) {
      return;
    }
    var columns = theProject.columnModel.columns || [];
    var changed = false;
    columns.forEach(function(column) {
      if (column && column.name && column.name.indexOf('路径') >= 0) {
        ResourceExplorer._filePathColumns[column.name] = { enabled: true, rootPath: '' };
        changed = true;
      }
    });
    if (changed) {
      ResourceExplorer._saveConfig();
    }
  };

  /**
   * Save configuration to localStorage
   */
  ResourceExplorer._saveConfig = function() {
    var key = ResourceExplorer._getStorageKey();
    if (key) {
      try {
        localStorage.setItem(key, JSON.stringify(ResourceExplorer._filePathColumns));
      } catch (e) {
        console.error('Failed to save ResourceExplorer config:', e);
      }
    }
  };

  /**
   * Check if a column is marked as file path column
   * @param {string} columnName - Column name to check
   * @returns {boolean}
   */
  ResourceExplorer.isFilePathColumn = function(columnName) {
    var config = ResourceExplorer._filePathColumns[columnName];
    return config && config.enabled === true;
  };

  /**
   * Toggle file path column status
   * @param {string} columnName - Column name to toggle
   * @returns {boolean} New enabled state
   */
  ResourceExplorer.toggleFilePathColumn = function(columnName) {
    if (!ResourceExplorer._filePathColumns[columnName]) {
      ResourceExplorer._filePathColumns[columnName] = {
        enabled: false,
        rootPath: ''
      };
    }
    
    var config = ResourceExplorer._filePathColumns[columnName];
    config.enabled = !config.enabled;
    
    ResourceExplorer._saveConfig();
    
    // Refresh data table to update cell rendering
    if (typeof ui !== 'undefined' && ui.dataTableView) {
      ui.dataTableView.render();
    }
    
    return config.enabled;
  };

  /**
   * Set root path for a file path column
   * @param {string} columnName - Column name
   * @param {string} rootPath - Root path for file exploration
   */
  ResourceExplorer.setRootPath = function(columnName, rootPath) {
    if (!ResourceExplorer._filePathColumns[columnName]) {
      ResourceExplorer._filePathColumns[columnName] = {
        enabled: false,
        rootPath: ''
      };
    }
    ResourceExplorer._filePathColumns[columnName].rootPath = rootPath;
    ResourceExplorer._saveConfig();
  };

  /**
   * Get root path for a file path column
   * @param {string} columnName - Column name
   * @returns {string} Root path or empty string
   */
  ResourceExplorer.getRootPath = function(columnName) {
    var config = ResourceExplorer._filePathColumns[columnName];
    return config ? (config.rootPath || '') : '';
  };

  /**
   * Get all file path column names
   * @returns {string[]} Array of column names marked as file path
   */
  ResourceExplorer.getFilePathColumns = function() {
    var columns = [];
    for (var name in ResourceExplorer._filePathColumns) {
      if (ResourceExplorer._filePathColumns[name].enabled) {
        columns.push(name);
      }
    }
    return columns;
  };

  /**
   * Wait for theProject and ui.dataTableView to be available, then initialize
   */
  ResourceExplorer._waitAndInit = function() {
    // Check if theProject is available（需列模型就绪后才能判定默认文件路径列）
    if (typeof theProject === 'undefined' || !theProject.id || !theProject.columnModel) {
      setTimeout(ResourceExplorer._waitAndInit, 200);
      return;
    }

    // Initialize config
    ResourceExplorer.initI18n();
    ResourceExplorer._loadConfig();
    ResourceExplorer._applyDefaultColumns();

    // Check if we have file path columns to restore
    var hasFilePathColumns = Object.keys(ResourceExplorer._filePathColumns).some(function(name) {
      return ResourceExplorer._filePathColumns[name].enabled;
    });

    if (hasFilePathColumns) {
      // Wait for dataTableView to be ready
      ResourceExplorer._waitForDataTableAndRender();
    }
  };

  /**
   * 等首屏数据真的出来后再重绘一次：空表重绘只会让人看到表格先被清空再恢复。
   * 重绘本身不重新拉行数据，只让「文件路径列 → 超链接」的配置生效。
   */
  ResourceExplorer._waitForDataTableAndRender = function(attempt) {
    attempt = attempt || 0;
    var rowModel = (typeof theProject !== 'undefined' && theProject) ? theProject.rowModel : null;
    var ready = typeof ui !== 'undefined' && ui.dataTableView && rowModel
        && rowModel.rows && rowModel.rows.length > 0;
    if (!ready) {
      // 最多等 5 秒；空项目直接放弃，不无限轮询
      if (attempt < 25) {
        setTimeout(function() { ResourceExplorer._waitForDataTableAndRender(attempt + 1); }, 200);
      }
      return;
    }

    console.log('[ResourceExplorer] Restoring file path column states, triggering re-render');
    ui.dataTableView.render();
  };

  // Initialize when document is ready
  $(document).ready(function() {
    ResourceExplorer._waitAndInit();
  });

})();

