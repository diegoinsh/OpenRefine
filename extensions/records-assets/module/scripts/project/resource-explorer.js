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
    // Check if theProject is available
    if (typeof theProject === 'undefined' || !theProject.id) {
      setTimeout(ResourceExplorer._waitAndInit, 200);
      return;
    }

    // Initialize config
    ResourceExplorer.initI18n();
    ResourceExplorer._loadConfig();

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
   * Wait for ui.dataTableView to be ready, then trigger render
   */
  ResourceExplorer._waitForDataTableAndRender = function() {
    if (typeof ui === 'undefined' || !ui.dataTableView) {
      setTimeout(ResourceExplorer._waitForDataTableAndRender, 200);
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

