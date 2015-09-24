package io.lambdacube.bndploy.install;

import com.google.common.collect.ImmutableList;

public final class Config {

    public final ImmutableList<String> runtimeDirs;

    public final ImmutableList<String> applicationDirs;

    public final boolean watchApplicationDirs;

    public final ImmutableList<String> singletonNamespaces;

    public final boolean updateOnlySnapshots;

    public Config(ImmutableList<String> runtimeDirs, ImmutableList<String> applicationDirs,
            boolean watchApplicationDirs, ImmutableList<String> singletonNamespaces, boolean updateOnlySnapshots) {
        this.runtimeDirs = runtimeDirs;
        this.applicationDirs = applicationDirs;
        this.watchApplicationDirs = watchApplicationDirs;
        this.singletonNamespaces = singletonNamespaces;
        this.updateOnlySnapshots = updateOnlySnapshots;
    }

}
