const { withSettingsGradle } = require("expo/config-plugins");

/**
 * expo-autolinking-settings-plugin defaults projectRoot to settings.rootDir
 * (the android/ folder), so local modules in <repo>/modules are never found.
 * Point it at the real project root before useExpoModules() runs.
 */
module.exports = function withLocalExpoModules(config) {
  return withSettingsGradle(config, (config) => {
    let contents = config.modResults.contents;
    if (!contents.includes("expoAutolinking.projectRoot")) {
      contents = contents.replace(
        "expoAutolinking.useExpoModules()",
        "expoAutolinking.projectRoot = rootDir.parentFile\nexpoAutolinking.useExpoModules()"
      );
      config.modResults.contents = contents;
    }
    return config;
  });
};
