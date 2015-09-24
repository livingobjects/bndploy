package io.lambdacube.bndploy.dirwatcher;

import java.nio.file.Path;
import java.util.List;

/**
 * Notifies when a file changed.
 * @author Simon Chemouil
 *
 */
public interface FileChangeListener {
    void filesCreated(List<Path> pathes);
    void filesUpdated(List<Path> pathes);
    void filesDeleted(List<Path> pathes);
}