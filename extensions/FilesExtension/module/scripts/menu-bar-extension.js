/**
 * 抽取扩展 - 项目页扩展栏菜单
 *
 * 「数据抽取」固定在扩展栏最前；「数据质量」由数据质量扩展自行注册，保持原样。
 * 同步注册（扩展脚本在 core 之后加载），确保 ExtensionBar 初始化时已包含本菜单。
 */
(function() {
  function install() {
    if (typeof ExtensionBar === 'undefined') {
      return false;
    }
    ExtensionBar.MenuItems.unshift({
      id: 'extraction',
      label: '数据抽取',
      submenu: [
        {
          id: 'extraction/edit-params',
          label: '编辑抽取参数',
          click: function() {
            ExtractionProjectPanel.open();
          }
        },
        {
          id: 'extraction/manage-rules',
          label: '管理抽取规则',
          click: function() {
            new ExtractionRulesDialog();
          }
        }
      ]
    });
    return true;
  }

  if (!install()) {
    $(install);
  }
})();