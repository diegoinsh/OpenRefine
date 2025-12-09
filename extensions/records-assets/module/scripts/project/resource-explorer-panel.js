/*
 * Resource Explorer Panel
 * Left panel component for resource exploration with hierarchical tree
 */

var ResourceExplorerPanel = {};

(function() {
  'use strict';

  // Panel state
  ResourceExplorerPanel._isVisible = false;
  ResourceExplorerPanel._currentRootPath = '';
  ResourceExplorerPanel._currentFilePath = '';
  ResourceExplorerPanel._panelElement = null;
  ResourceExplorerPanel._expandedPaths = {}; // Track expanded folders
  ResourceExplorerPanel._selectedItem = null; // Currently selected item element
  ResourceExplorerPanel._selectedPath = ''; // Currently selected item path

  /**
   * Show the resource explorer panel
   * @param {string} rootPath - Root path for exploration
   * @param {string} filePath - Initial file path to explore
   */
  ResourceExplorerPanel.show = function(rootPath, filePath) {
    ResourceExplorerPanel._currentRootPath = rootPath || '';
    ResourceExplorerPanel._currentFilePath = filePath || '';

    if (!ResourceExplorerPanel._panelElement) {
      ResourceExplorerPanel._createPanel();
    }

    // Show panel and hide original tabs
    ResourceExplorerPanel._panelElement.show();
    if (ui && ui.leftPanelTabs) {
      ui.leftPanelTabs.hide();
    }

    ResourceExplorerPanel._isVisible = true;

    // Load and auto-expand the initial path
    ResourceExplorerPanel._loadAndExpandPath(rootPath, filePath);
  };

  /**
   * Hide the resource explorer panel
   */
  ResourceExplorerPanel.hide = function() {
    if (ResourceExplorerPanel._panelElement) {
      ResourceExplorerPanel._panelElement.hide();
    }

    // Show original tabs
    if (ui && ui.leftPanelTabs) {
      ui.leftPanelTabs.show();
    }

    ResourceExplorerPanel._isVisible = false;
  };

  /**
   * Toggle panel visibility
   */
  ResourceExplorerPanel.toggle = function() {
    if (ResourceExplorerPanel._isVisible) {
      ResourceExplorerPanel.hide();
    } else {
      ResourceExplorerPanel.show(
        ResourceExplorerPanel._currentRootPath,
        ResourceExplorerPanel._currentFilePath
      );
    }
  };

  /**
   * Create the panel element
   */
  ResourceExplorerPanel._createPanel = function() {
    var panel = $('<div>')
      .attr('id', 'resource-explorer-panel')
      .addClass('resource-explorer-panel');

    // Header with close button
    var header = $('<div>')
      .addClass('resource-explorer-header')
      .appendTo(panel);

    $('<span>')
      .addClass('resource-explorer-title')
      .text($.i18n('records.assets.explorer.title') || 'Resource Explorer')
      .appendTo(header);

    var headerButtons = $('<div>')
      .addClass('resource-explorer-header-buttons')
      .appendTo(header);

    // Collapse button (same as original)
    $('<button>')
      .addClass('resource-explorer-collapse-btn')
      .attr('title', $.i18n('core-index/hide-panel') || 'Hide panel')
      .on('click', function() {
        ResourceExplorerPanel.hide();
        Refine._showHideLeftPanel();
      })
      .appendTo(headerButtons);

    // Close button (returns to original tabs)
    $('<button>')
      .addClass('resource-explorer-close-btn')
      .attr('title', $.i18n('records.assets.explorer.close') || 'Close')
      .html('&times;')
      .on('click', function() {
        ResourceExplorerPanel.hide();
      })
      .appendTo(headerButtons);

    // Tree container (scrollable, focusable for keyboard scrolling)
    var treeContainer = $('<div>')
      .attr('id', 'resource-explorer-tree-container')
      .attr('tabindex', '0')
      .addClass('resource-explorer-tree-container')
      .appendTo(panel);

    // Append to left panel
    if (ui && ui.leftPanelDiv) {
      panel.appendTo(ui.leftPanelDiv);
    } else {
      panel.appendTo($('#left-panel'));
    }

    ResourceExplorerPanel._panelElement = panel;
    panel.hide();
  };

  /**
   * Load and auto-expand the initial path
   */
  ResourceExplorerPanel._loadAndExpandPath = function(rootPath, filePath) {
    var container = $('#resource-explorer-tree-container');
    container.empty();

    // Extract directory from file path
    var dirPath = filePath ? filePath.replace(/[\/\\][^\/\\]*$/, '') : '';
    if (!dirPath && filePath) {
      dirPath = filePath;
    }

    // Mark initial path as expanded
    ResourceExplorerPanel._expandedPaths[dirPath] = true;
    ResourceExplorerPanel._currentRootPath = dirPath;

    // Create root node and load its contents
    ResourceExplorerPanel._createRootNode(container, dirPath);
  };

  /**
   * Create root node with path display
   */
  ResourceExplorerPanel._createRootNode = function(container, rootPath) {
    // Path bar
    var pathBar = $('<div>')
      .addClass('resource-explorer-path-bar')
      .appendTo(container);

    $('<span>')
      .addClass('path-label')
      .text($.i18n('records.assets.list.path') || 'Path')
      .appendTo(pathBar);

    $('<span>')
      .addClass('path-value')
      .text(rootPath)
      .attr('title', rootPath)
      .appendTo(pathBar);

    // Refresh button
    $('<button>')
      .addClass('resource-explorer-refresh-btn')
      .text($.i18n('records.assets.list.refresh') || 'Refresh')
      .on('click', function() {
        ResourceExplorerPanel._expandedPaths = {};
        ResourceExplorerPanel._loadAndExpandPath('', rootPath);
      })
      .appendTo(pathBar);

    // Tree list container
    var treeList = $('<div>')
      .addClass('resource-explorer-tree-list')
      .appendTo(container);

    // Load root folder contents
    ResourceExplorerPanel._loadFolderContents(treeList, rootPath, 0);
  };

  /**
   * Load folder contents and render as tree nodes
   * @param {jQuery} container - Container to append items to
   * @param {string} folderPath - Full path to folder
   * @param {number} level - Indentation level
   */
  ResourceExplorerPanel._loadFolderContents = function(container, folderPath, level) {
    var loadingEl = $('<div>')
      .addClass('tree-loading')
      .css('padding-left', (level * 20 + 8) + 'px')
      .text($.i18n('records.assets.preview.loading') || 'Loading...')
      .appendTo(container);

    $.ajax({
      url: '/command/records-assets/list',
      type: 'GET',
      data: { root: '', path: folderPath, page: 1, pageSize: 500 },
      dataType: 'json',
      success: function(data) {
        loadingEl.remove();
        if (data.status === 'ok' || data.status === 'success') {
          var fullPath = data.fullPath || folderPath;
          ResourceExplorerPanel._renderTreeItems(container, data.items || [], fullPath, level);
        } else {
          $('<div>')
            .addClass('tree-error')
            .css('padding-left', (level * 20 + 8) + 'px')
            .text(data.message || 'Error')
            .appendTo(container);
        }
      },
      error: function() {
        loadingEl.remove();
        $('<div>')
          .addClass('tree-error')
          .css('padding-left', (level * 20 + 8) + 'px')
          .text($.i18n('records.assets.errors.connectionError') || 'Error')
          .appendTo(container);
      }
    });
  };

  /**
   * Render tree items with proper hierarchy
   */
  ResourceExplorerPanel._renderTreeItems = function(container, items, parentPath, level) {
    if (items.length === 0) {
      $('<div>')
        .addClass('tree-empty')
        .css('padding-left', (level * 20 + 8) + 'px')
        .text($.i18n('records.assets.list.empty') || 'Empty folder')
        .appendTo(container);
      return;
    }

    // Sort: folders first, then files
    items.sort(function(a, b) {
      if (a.isDirectory && !b.isDirectory) return -1;
      if (!a.isDirectory && b.isDirectory) return 1;
      return a.name.localeCompare(b.name);
    });

    items.forEach(function(item) {
      var itemFullPath = parentPath + '/' + item.name;
      var isExpanded = ResourceExplorerPanel._expandedPaths[itemFullPath];

      // Create item row
      var itemRow = $('<div>')
        .addClass('tree-item')
        .addClass(item.isDirectory ? 'tree-folder' : 'tree-file')
        .css('padding-left', (level * 20) + 'px')
        .appendTo(container);

      // Expand/collapse toggle for folders
      var toggleIcon = $('<span>')
        .addClass('tree-toggle')
        .text(item.isDirectory ? (isExpanded ? '▼' : '▶') : ' ')
        .appendTo(itemRow);

      // Item icon
      var icon = item.isDirectory ? '📁' : ResourceExplorerPanel._getFileIcon(item.name);
      $('<span>')
        .addClass('tree-icon')
        .text(icon)
        .appendTo(itemRow);

      // Item name
      $('<span>')
        .addClass('tree-name')
        .text(item.name)
        .attr('title', itemFullPath)
        .appendTo(itemRow);

      // Children container for folders
      var childrenContainer = null;
      if (item.isDirectory) {
        childrenContainer = $('<div>')
          .addClass('tree-children')
          .appendTo(container);

        if (isExpanded) {
          childrenContainer.show();
          ResourceExplorerPanel._loadFolderContents(childrenContainer, itemFullPath, level + 1);
        } else {
          childrenContainer.hide();
        }
      }

      // Click handler
      itemRow.on('click', function(e) {
        e.stopPropagation();
        // Always select the clicked item
        ResourceExplorerPanel._selectItem(itemRow, itemFullPath);

        if (item.isDirectory) {
          ResourceExplorerPanel._toggleFolder(itemFullPath, toggleIcon, childrenContainer, level + 1);
        } else {
          ResourceExplorerPanel._onFileClick(itemFullPath, item);
        }
      });
    });
  };

  /**
   * Toggle folder expand/collapse
   */
  ResourceExplorerPanel._toggleFolder = function(folderPath, toggleIcon, childrenContainer, level) {
    var isExpanded = ResourceExplorerPanel._expandedPaths[folderPath];

    if (isExpanded) {
      // Collapse
      ResourceExplorerPanel._expandedPaths[folderPath] = false;
      toggleIcon.text('▶');
      childrenContainer.slideUp(150);
    } else {
      // Expand
      ResourceExplorerPanel._expandedPaths[folderPath] = true;
      toggleIcon.text('▼');
      childrenContainer.empty().show();
      ResourceExplorerPanel._loadFolderContents(childrenContainer, folderPath, level);
    }
  };

  /**
   * Get file icon based on extension
   */
  ResourceExplorerPanel._getFileIcon = function(filename) {
    var ext = filename.split('.').pop().toLowerCase();
    var iconMap = {
      'jpg': '🖼️', 'jpeg': '🖼️', 'png': '🖼️', 'gif': '🖼️', 'bmp': '🖼️', 'svg': '🖼️', 'webp': '🖼️',
      'pdf': '📄',
      'doc': '📝', 'docx': '📝',
      'xls': '📊', 'xlsx': '📊',
      'txt': '📃', 'md': '📃', 'json': '📃', 'xml': '📃',
      'zip': '📦', 'rar': '📦', '7z': '📦', 'tar': '📦', 'gz': '📦',
      'mp4': '🎬', 'avi': '🎬', 'mov': '🎬', 'mkv': '🎬',
      'mp3': '🎵', 'wav': '🎵', 'flac': '🎵'
    };
    return iconMap[ext] || '📄';
  };

  /**
   * Handle file click - show preview
   */
  ResourceExplorerPanel._onFileClick = function(fileFullPath, item) {
    if (typeof FilePreviewDialog !== 'undefined') {
      FilePreviewDialog.show('', fileFullPath, item);
    } else {
      console.log('[ResourceExplorerPanel] FilePreviewDialog not available for:', fileFullPath);
    }
  };

  /**
   * Select an item (highlight it)
   */
  ResourceExplorerPanel._selectItem = function(itemElement, itemPath) {
    // Remove previous selection
    if (ResourceExplorerPanel._selectedItem) {
      ResourceExplorerPanel._selectedItem.removeClass('tree-item-selected');
    }

    // Set new selection
    ResourceExplorerPanel._selectedItem = itemElement;
    ResourceExplorerPanel._selectedPath = itemPath;

    if (itemElement) {
      itemElement.addClass('tree-item-selected');

      // Scroll into view if needed
      var container = $('#resource-explorer-tree-container');
      var containerTop = container.scrollTop();
      var containerHeight = container.height();
      var itemTop = itemElement.position().top + containerTop;
      var itemHeight = itemElement.outerHeight();

      if (itemTop < containerTop) {
        container.scrollTop(itemTop);
      } else if (itemTop + itemHeight > containerTop + containerHeight) {
        container.scrollTop(itemTop + itemHeight - containerHeight);
      }
    }
  };

  /**
   * Get all visible tree items in order
   */
  ResourceExplorerPanel._getVisibleItems = function() {
    return $('#resource-explorer-tree-container .tree-item:visible');
  };

  /**
   * Navigate to previous item (left arrow)
   */
  ResourceExplorerPanel._navigatePrevious = function() {
    var items = ResourceExplorerPanel._getVisibleItems();
    if (items.length === 0) return;

    var currentIndex = -1;
    if (ResourceExplorerPanel._selectedItem) {
      items.each(function(index) {
        if ($(this).is(ResourceExplorerPanel._selectedItem)) {
          currentIndex = index;
          return false;
        }
      });
    }

    var newIndex = currentIndex > 0 ? currentIndex - 1 : items.length - 1;
    var newItem = $(items[newIndex]);
    var itemPath = newItem.find('.tree-name').attr('title') || '';

    ResourceExplorerPanel._selectItem(newItem, itemPath);

    // If it's a file, open preview
    if (newItem.hasClass('tree-file')) {
      var filename = newItem.find('.tree-name').text();
      ResourceExplorerPanel._onFileClick(itemPath, { name: filename });
    }
  };

  /**
   * Navigate to next item (right arrow)
   */
  ResourceExplorerPanel._navigateNext = function() {
    var items = ResourceExplorerPanel._getVisibleItems();
    if (items.length === 0) return;

    var currentIndex = -1;
    if (ResourceExplorerPanel._selectedItem) {
      items.each(function(index) {
        if ($(this).is(ResourceExplorerPanel._selectedItem)) {
          currentIndex = index;
          return false;
        }
      });
    }

    var newIndex = currentIndex < items.length - 1 ? currentIndex + 1 : 0;
    var newItem = $(items[newIndex]);
    var itemPath = newItem.find('.tree-name').attr('title') || '';

    ResourceExplorerPanel._selectItem(newItem, itemPath);

    // If it's a file, open preview
    if (newItem.hasClass('tree-file')) {
      var filename = newItem.find('.tree-name').text();
      ResourceExplorerPanel._onFileClick(itemPath, { name: filename });
    }
  };

  /**
   * Handle keyboard events
   */
  ResourceExplorerPanel._handleKeyDown = function(e) {
    // Only handle when panel is visible
    if (!ResourceExplorerPanel._isVisible) return;

    // Only handle left/right arrows, don't interfere with other keys
    // Left arrow - previous item
    if (e.keyCode === 37) {
      e.preventDefault();
      ResourceExplorerPanel._navigatePrevious();
    }
    // Right arrow - next item
    else if (e.keyCode === 39) {
      e.preventDefault();
      ResourceExplorerPanel._navigateNext();
    }
    // Enter - toggle folder or open file (only when item selected)
    else if (e.keyCode === 13 && ResourceExplorerPanel._selectedItem) {
      e.preventDefault();
      ResourceExplorerPanel._selectedItem.trigger('click');
    }
    // Don't prevent default for up/down arrows - let them scroll normally
  };

  // Register keyboard event handler
  $(document).on('keydown', ResourceExplorerPanel._handleKeyDown);

})();

