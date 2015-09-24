package io.lambdacube.bndploy.install;

import com.google.common.base.Splitter;
import com.google.common.collect.Iterables;

public final class BundleUtils {

    public static String getBsn(String headerBsn) {
        return Iterables.getFirst(Splitter.on(';').split(headerBsn), null);
    }
}
