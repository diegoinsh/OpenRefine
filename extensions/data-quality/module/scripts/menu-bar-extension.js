/**
 * Data Quality Extension - Menu Bar Extension
 *
 * Registers the "Data Quality" menu in the ExtensionBar
 */

I18NUtil.init("data-quality");

/**
 * 是否以「仅规则面板」的嵌入形态打开。
 * 平台对外地址形如 /app/project?project=<id>&view=quality-rules，
 * 供业务系统 iframe 直接嵌入规则配置界面；不带该参数时行为与原来完全一致。
 */
function isQualityEmbedRequest() {
  try {
    return new URLSearchParams(window.location.search).get('view') === 'quality-rules';
  } catch (e) {
    return false;
  }
}

// Extend the ExtensionBar with Data Quality menu
$(function() {
  ExtensionBar.MenuItems.push({
    "id": "data-quality",
    "label": $.i18n('data-quality-extension/menu-label'),
    "submenu": [
      {
        id: "data-quality/edit-rules",
        label: $.i18n('data-quality-extension/edit-rules'),
        click: function() {
          QualityAlignment.launch(false);
        }
      },
      {
        id: "data-quality/manage-rules",
        label: $.i18n('data-quality-extension/manage-rules'),
        click: function() {
          new ManageRulesDialog();
        }
      },
      {},  // separator
      {
        id: "data-quality/run-check",
        label: $.i18n('data-quality-extension/run-check'),
        click: function() {
          new RunCheckDialog();
        }
      }
    ]
  });

  // Auto-launch quality alignment tabs if rules or results exist
  // Use a small delay to ensure the UI is fully loaded
  console.log('[menu-bar-extension] Setting up auto-launch timeout...');
  setTimeout(function() {
    console.log('[menu-bar-extension] Timeout fired, QualityAlignment defined:', typeof QualityAlignment !== 'undefined');
    if (typeof QualityAlignment !== 'undefined') {
      // 嵌入形态（?view=quality-rules）：只显示规则面板，隐藏顶栏/左侧面/数据网格，
      // 供业务系统 iframe 直接嵌入；规则保存后对之后启动的检查任务生效。
      if (isQualityEmbedRequest()) {
        // 标记打在 <html> 上（project.vt 的 <head> 脚本已提前加过，这里兜底）
        document.documentElement.classList.add('ruyi-embed-quality');
        // 必须先拉取项目已存规则再渲染：直接 launch 只会搭出空面板，
        // 客户一保存就会把原有规则覆盖成空。该入口加载完规则后自动切到规则页签。
        QualityAlignment.autoLaunchIfNeededWithTarget('rules');
        return;
      }
      // Check URL hash to activate specific tab
      var hash = window.location.hash;
      console.log('[menu-bar-extension] URL hash:', hash);

      // Always use autoLaunchIfNeeded to load saved rules/results first
      // Then switch to the requested tab based on hash
      if (hash === '#quality-rules' || hash === '#quality-results') {
        // Clear hash to prevent infinite loop on refresh
        history.replaceState(null, '', window.location.pathname + window.location.search);

        // Load saved data and launch with target tab
        QualityAlignment.autoLaunchIfNeededWithTarget(hash === '#quality-results' ? 'results' : 'rules');
      } else {
        // Normal auto-launch based on existing rules/results
        console.log('[menu-bar-extension] Calling autoLaunchIfNeeded...');
        QualityAlignment.autoLaunchIfNeeded();
      }
    } else {
      console.error('[menu-bar-extension] QualityAlignment is not defined!');
    }
  }, 500);
});

