package io.lambdacube.bndploy.install;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import io.lambdacube.bndploy.dirwatcher.DirWatcher;
import io.lambdacube.bndploy.dirwatcher.FileChangeListener;
import io.lambdacube.bndploy.install.BundleChecker.Action;
import org.ops4j.pax.tinybundles.core.TinyBundles;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class BundleInstaller implements BundleActivator {

    private static final Logger LOGGER = LoggerFactory.getLogger(BundleInstaller.class);

    public final ConfigReader configReader = new ConfigReader();

    private BundleContext context;

    private BundleChecker bundleChecker;

    private Config config;

    private Map<File, DirWatcher> watchers = Maps.newConcurrentMap();

    @Override
    public void start(BundleContext context) {
        this.context = context;
        config = configReader.getConfig();
        bundleChecker = new BundleChecker(context, config);

        deployRuntime(config.runtimeDirs);

        deployApplications(config.applicationDirs);

    }

    private void deployRuntime(ImmutableList<String> runtimeDirs) {
        LOGGER.info("Installing runtime bundles from : {}", Joiner.on(", ").join(runtimeDirs));
        ImmutableList.Builder<Bundle> bundlesBuilder = ImmutableList.builder();
        for (String dir : runtimeDirs) {
            bundlesBuilder.addAll(installDirectory(new File(dir)));
        }
        ImmutableList<Bundle> bundles = bundlesBuilder.build();
        LOGGER.info("Starting {} runtime bundles", bundles.size());
        startBundles(bundles);
    }

    private void deployApplications(ImmutableList<String> applicationDirs) {
        LOGGER.info("Installing application bundles from : {}", Joiner.on(", ").join(applicationDirs));
        ImmutableList.Builder<Bundle> bundlesBuilder = ImmutableList.builder();
        for (String dir : applicationDirs) {
            File fileDir = new File(dir);
            bundlesBuilder.addAll(installDirectory(fileDir));
            if (config.watchApplicationDirs) {
                watchers.put(fileDir, createDirWatcher(fileDir));
            }
        }

        List<Bundle> bundles = bundlesBuilder.build();
        LOGGER.info("Starting {} application bundles", bundles.size());
        startBundles(bundles);

        for (DirWatcher watcher : watchers.values()) {
            try {
                watcher.start();
            } catch (IOException e) {
                LOGGER.error("Couldn't start dirwatcher", e);
            }
        }
    }

    private ImmutableList<Bundle> installDirectory(File dir) {
        if (!dir.exists()) {
            return ImmutableList.of();
        }
        ImmutableList.Builder<Bundle> bundlesBuilder = ImmutableList.builder();

        File[] jarFiles = dir.listFiles((dir1, name) -> name.endsWith(".jar"));
        if (jarFiles != null) {
            for (File file : jarFiles) {
                Bundle bundle = installOrUpdateBundle(file, false);
                if (bundle != null) {
                    bundlesBuilder.add(bundle);
                }
            }
        }

        File[] subDirs = dir.listFiles(File::isDirectory);
        if (subDirs != null) {
            for (File subDir : subDirs) {
                bundlesBuilder.addAll(installDirectory(subDir));
            }
        }

        return bundlesBuilder.build();
    }

    private DirWatcher createDirWatcher(File fileDir) {
        FileChangeListener listener = new FileChangeListener() {

            @Override
            public void filesCreated(List<Path> pathes) {
                List<Bundle> bundles = Lists.newArrayList();
                for (Path path : pathes) {
                    File file = path.toFile();
                    if (file.isDirectory()) {
                        bundles.addAll(installDirectory(file));
                    } else {
                        Bundle b = installOrUpdateBundle(file, false);
                        if (b != null) {
                            bundles.add(b);
                        }
                    }
                }
                startBundles(bundles);
            }

            @Override
            public void filesUpdated(List<Path> pathes) {
                List<Bundle> bundles = pathes.stream()
                        .flatMap(path -> {
                            if (Files.isDirectory(path)) {
                                return installDirectory(path.toFile()).stream();
                            } else {
                                return Stream.of(installOrUpdateBundle(path.toFile(), true));
                            }
                        })
                        .collect(Collectors.toList());
                startBundles(bundles);
            }

            @Override
            public void filesDeleted(List<Path> pathes) {

            }
        };

        return new DirWatcher(fileDir.toPath(), 1500, listener);
    }

    private Bundle installOrUpdateBundle(File file, boolean update) {
        try (JarFile jarFile = new JarFile(file)) {

            Action action = bundleChecker.getAction(jarFile, update);
            if (action == Action.NONE) {
                return null;
            }
            try (FileInputStream inputStream = new FileInputStream(file)) {

                Bundle bundle = null;
                String location = makeLocation(jarFile);
                switch (action) {
                    case INSTALL:
                        LOGGER.info("Installing bundle {}", location);
                        bundle = context.installBundle(location, inputStream);
                        break;
                    case UPDATE:
                        bundle = context.getBundle(location);
                        if (bundle != null) {
                            LOGGER.info("Updating bundle {}", location);
                            bundle.stop();
                            bundle.update(inputStream);
                        } else {
                            LOGGER.warn("Not updating core bundle {}", location);
                        }
                        break;
                    case WRAP_AND_INSTALL:
                        LOGGER.info("Wrapping JAR {}", location);
                        InputStream wrappingStream = TinyBundles.bundle().read(inputStream)
                                .set("Bundle-SymbolicName", location)
                                .build(TinyBundles.withClassicBuilder());
                        bundle = context.installBundle(location, wrappingStream);
                        break;
                    case STOP_FRAMEWORK:
                        LOGGER.info("Stopping the framework!");
                        context.getBundle(0).stop();
                    default:
                        break;
                }
                return bundle;

            } catch (BundleException e) {
                LOGGER.error("Error while installing bundle at {}", file, e);
            }

        } catch (IOException e) {
            LOGGER.error("Exception while trying to install or update file: {}", file, e);
        }
        return null;
    }

    private void startBundles(Iterable<Bundle> bundles) {
        for (Bundle b : bundles) {
            try {
                b.start();
            } catch (BundleException e) {
                LOGGER.error("Couldn't start bundle {}", b.getSymbolicName(), e);
            }
        }
    }

    private String makeLocation(JarFile jarFile) throws IOException {
        StringBuilder buf = new StringBuilder();
        final Manifest manifest = jarFile.getManifest();
        String bsn = manifest.getMainAttributes().getValue("Bundle-SymbolicName");
        final String version = manifest.getMainAttributes().getValue("Bundle-Version");

        if (bsn != null) {
            bsn = BundleUtils.getBsn(bsn);
            buf.append(bsn);
            if (version != null) {
                buf.append(':');
                buf.append(version);
            }
        } else {
            buf.append(jarFile.getName());
        }
        return buf.toString();
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        bundleChecker.dispose();

        if (config.watchApplicationDirs) {
            for (DirWatcher watcher : watchers.values()) {
                watcher.stop();
            }
        }
    }

}
