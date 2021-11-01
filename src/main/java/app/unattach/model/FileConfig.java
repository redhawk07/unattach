package app.unattach.model;

import app.unattach.utils.Logger;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;

public class FileConfig extends BaseConfig {
  private static final Logger logger = Logger.get();

  public FileConfig() {
    loadConfig();
  }

  public FileConfig(File configFile) {
    loadConfigFromFile(configFile);
  }

  @Override
  public void loadConfig() {
    loadConfigFromFile(getConfigPath().toFile());
  }  

  public void loadConfigFromFile(File configFile) {
    if (configFile.exists()) {
      try (FileInputStream in = new FileInputStream(configFile)) {
        config.load(in);
      } catch (IOException e) {
        logger.error("Failed to load the config file.", e);
      }
    }
  }

  @Override
  public void saveConfig() {
    saveConfigToFile(getConfigPath().toFile());
  }

  public void saveConfigToFile(File configFile) {
    removeUnknownProperties();
    try (FileOutputStream out = new FileOutputStream(configFile)) {
      config.store(out, null);
    } catch (IOException e) {
      logger.error("Failed to save the config file.", e);
    }
  }

  private static Path getConfigPath() {
    String userHome = System.getProperty("user.home");
    return Paths.get(userHome, "." + Constants.PRODUCT_NAME.toLowerCase() + ".properties");
  }

  private void removeUnknownProperties() {
    HashSet<Object> unknownProperties = new HashSet<>(config.keySet());
    unknownProperties.removeAll(getPropertyNames());
    unknownProperties.forEach(config::remove);
  }
}
