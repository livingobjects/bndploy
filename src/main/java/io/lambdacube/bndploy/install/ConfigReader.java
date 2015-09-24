package io.lambdacube.bndploy.install;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ConfigReader {
    private static final String CONFIG_DIR = "conf";

    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigReader.class);

    private static final String RUNTIME_DIRS = "installer.runtime.dirs";
    private static final String RUNTIME_DIRS_DEFAULT = "runtime";
    private static final String APPLICATION_DIRS = "installer.application.dirs";
    private static final String APPLICATION_DIRS_DEFAULT = "application";

    private static final String UNIQUE_BSN_STARTSWITH = "installer.singletonNamespaces";
    private static final String UNIQUE_BSN_STARTSWITH_DEFAULT = "";
    
    private static final String UPDATE_ONLY_SNAPSHOTS = "installer.updateOnlySnapshots";
    private static final String UPDATE_ONLY_SNAPSHOTS_DEFAULT = "true";


    private static final String WATCH_APPLICATION_DIRS = "installer.application.dirs.watch";
    private static final String WATCH_APPLICATION_DIRS_DEFAULT = "true";

    private static final String CONFIG_PROPERTIES = "installer.cfg.properties";

    private static final Config DEFAULT_CONFIG = new Config(ImmutableList.of(RUNTIME_DIRS_DEFAULT),
            ImmutableList.of(APPLICATION_DIRS_DEFAULT), true, ImmutableList.of(), true);


    public Config getConfig() {
        Properties prop = new Properties();

        File propertiesFile = new File(CONFIG_DIR, CONFIG_PROPERTIES);
        if (!propertiesFile.exists()) {
            LOGGER.info("Couldn't read config, using default");
            return DEFAULT_CONFIG;
        }

        try (InputStream inputStream = new FileInputStream(propertiesFile)) {
            if (inputStream != null) {
                prop.load(inputStream);

                ImmutableList<String> runtimeDirs = ImmutableList.copyOf(Splitter.on(',').trimResults()
                        .split(prop.getProperty(RUNTIME_DIRS, RUNTIME_DIRS_DEFAULT)));
                ImmutableList<String> applicationDirs = ImmutableList.copyOf(Splitter.on(',').trimResults()
                        .split(prop.getProperty(APPLICATION_DIRS, APPLICATION_DIRS_DEFAULT)));

                ImmutableList<String> singletonNamespaces = ImmutableList.copyOf(Splitter.on(',').trimResults().omitEmptyStrings()
                        .split(prop.getProperty(UNIQUE_BSN_STARTSWITH, UNIQUE_BSN_STARTSWITH_DEFAULT)));

                boolean watchAppDirs = Boolean.valueOf(prop.getProperty(WATCH_APPLICATION_DIRS,
                        WATCH_APPLICATION_DIRS_DEFAULT));
                
                boolean updateOnlySnapshots = Boolean.valueOf(prop.getProperty(UPDATE_ONLY_SNAPSHOTS,
                        UPDATE_ONLY_SNAPSHOTS_DEFAULT));


                return new Config(runtimeDirs, applicationDirs, watchAppDirs, singletonNamespaces, updateOnlySnapshots);
            }

        } catch (IOException e1) {
            LOGGER.error("Couldn't read config, using default.");
        }

        return DEFAULT_CONFIG;
    }

}
