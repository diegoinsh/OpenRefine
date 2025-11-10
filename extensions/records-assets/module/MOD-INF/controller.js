var IM = Packages.com.google.refine.importing.ImportingManager;
var RecordsAssetsController = Packages.com.google.refine.extension.records.assets.RecordsAssetsController;

IM.registerController(
  module,
  "records-assets-controller",
  new RecordsAssetsController()
);

ClientSideResourceManager.addPaths(
  "project/scripts",
  module,
  [
    "scripts/index/records-assets-controller.js"
  ]
);

ClientSideResourceManager.addPaths(
  "project/styles",
  module,
  [
    "styles/records-assets.css"
  ]
);

