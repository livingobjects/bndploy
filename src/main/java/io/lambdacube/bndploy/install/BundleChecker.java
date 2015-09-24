package io.lambdacube.bndploy.install;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Version;
import org.osgi.util.tracker.BundleTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BundleChecker {
    private static final String SNAPSHOT_QUALIFIER = "snapshot";

    public enum Action {
        NONE, UPDATE, INSTALL, WRAP_AND_INSTALL, STOP_FRAMEWORK
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(BundleChecker.class);

    private final BundleTracker<Bundle> tracker;
    private final Config config;

    public BundleChecker(BundleContext context, Config config) {
        this.config = config;
        this.tracker = new BundleTracker<Bundle>(context, Bundle.INSTALLED | Bundle.RESOLVED | Bundle.STARTING
                | Bundle.ACTIVE, null);
        this.tracker.open();
    }

    public Action getAction(JarFile jarFile, boolean update) {

        try {
            Manifest manifest = jarFile.getManifest();
            String headerBsn = manifest.getMainAttributes().getValue("Bundle-SymbolicName");
            final Version version = Version.parseVersion(manifest.getMainAttributes().getValue("Bundle-Version"));

            if (headerBsn == null) {
                // not a bundle, install blindly (webjars?)
                return Action.WRAP_AND_INSTALL;
            }

            String bsn = BundleUtils.getBsn(headerBsn);

            Bundle[] allInstalledBundles = tracker.getBundles();

            List<Bundle> installedBundles = allInstalledBundles != null ? Stream.of(allInstalledBundles)
                    .filter(b -> Objects.equals(b.getSymbolicName(), bsn))
                    .collect(Collectors.toList()) : Collections.emptyList();

            if (installedBundles.isEmpty()) {
                return Action.INSTALL;
            } else {
                if (wantUnique(bsn)) {
                    Bundle b = installedBundles.get(0);
                    if (version.equals(b.getVersion())) {
                        LOGGER.debug("Bundle {} with version {} already installed", bsn, version);
                        
                    
                        // The same bundle is already present, check if they're
                        // really the same or display an error
                        String installedLastModified = b.getHeaders().get("Bnd-LastModified");
                        String newLastModified = manifest.getMainAttributes().getValue("Bnd-LastModified");
                        if (newLastModified != null && installedLastModified != null) {
                            long ilm = Long.parseLong(installedLastModified);
                            long nlm = Long.parseLong(newLastModified);
                            if (nlm != ilm) {
                                if (!SNAPSHOT_QUALIFIER.equalsIgnoreCase(version.getQualifier())) {
                                    LOGGER.error(
                                            "Different contents for bundle {} version {} that is not a snapshot!!!",
                                            bsn, version);
                                    return Action.NONE;
                                }
                                LOGGER.warn("Different contents for bundle {} version {}, trying to update to newest",
                                        bsn, version);
                            }
                            return (nlm > ilm) ? Action.UPDATE : Action.NONE;
                        }
                    } else {
                        LOGGER.error("#################################################################");
                        LOGGER.error("/!\\ Bundle {} that we want unique is present with two versions: {} and {}", bsn,
                                version, b.getVersion());
                        LOGGER.error("Stopping the framework!");
                        LOGGER.error("#################################################################");
                        return Action.STOP_FRAMEWORK;
                    }
                } else {
                    for (Bundle b : installedBundles) {
                        if (version.equals(b.getVersion())) {
                            if (SNAPSHOT_QUALIFIER.equalsIgnoreCase(version.getQualifier()) && update) {
                                LOGGER.warn("Snapshot bundle {} with version {} already installed, trying to update",
                                        bsn, version);
                                return Action.UPDATE;
                            } else {
                                return Action.NONE;
                            }
                        } else {
                            return Action.INSTALL;
                        }
                    }
                }

            }

        } catch (IOException e) {
            LOGGER.trace("Error while reading manifest", e);
        }

        return Action.NONE;

    }

    private boolean wantUnique(String bsn) {
        for (String bsnStart : config.singletonNamespaces) {
            if (bsn.startsWith(bsnStart)) {
                return true;
            }
        }
        return false;
    }

    public void dispose() {
        this.tracker.close();
    }

}
