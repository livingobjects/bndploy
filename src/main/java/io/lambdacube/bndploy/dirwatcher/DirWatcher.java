package io.lambdacube.bndploy.dirwatcher;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ThreadFactory;

import com.google.common.base.Joiner;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Using WatchService to watch recursively, with a trigger time to avoid an
 * event storm (e.g: caused by IDE refactorings)
 * 
 * @author Simon Chemouil
 *
 */
public class DirWatcher {
    private static final Logger LOGGER = LoggerFactory.getLogger(DirWatcher.class);

    private Path watchedDir;
    private FileChangeListener listener;

    private WatchService watchService;
    private Thread watchThread;
    private BiMap<Path, WatchKey> pathToKeyMap;

    private ThreadFactory threadFactory;

    private Set<Path> currentCreated = Sets.newConcurrentHashSet();
    private Set<Path> currentModified = Sets.newConcurrentHashSet();
    private Set<Path> currentDeleted = Sets.newConcurrentHashSet();

    private Timer timer;

    private int triggerTime;

    public DirWatcher(Path watchedDir, int triggerTime, FileChangeListener listener, ThreadFactory threadFactory) {
        this.watchedDir = watchedDir;
        this.triggerTime = triggerTime;
        this.listener = listener;
        this.threadFactory = threadFactory;

        this.watchService = null;
        this.watchThread = null;
        this.pathToKeyMap = HashBiMap.create();

        this.timer = null;
    }

    public DirWatcher(Path watchedDir, int triggerTime, FileChangeListener listener) {
        this(watchedDir, triggerTime, listener, r -> new Thread(r, "WatchService Thread"));
    }

    public void start() throws IOException {
        LOGGER.info("Watching directory {} recursively", watchedDir);
        watchService = FileSystems.getDefault().newWatchService();

        watchThread = threadFactory.newThread((new Runnable() {
            @Override
            public void run() {
                installWatcherRecursively();

                while (!Thread.interrupted()) {
                    try {
                        WatchKey watchKey = watchService.take();
                        Path dirPath = pathToKeyMap.inverse().get(watchKey);
                        List<WatchEvent<?>> pollEvents = watchKey.pollEvents();
                        
                        watchKey.reset();
                        resetTrigger(dirPath, pollEvents);
                    } catch (ClosedWatchServiceException | InterruptedException e) {
                        break;
                    }
                }
            }
        }));

        watchThread.start();
    }

    public synchronized void stop() {
        if (watchThread != null) {
            try {
                watchService.close();
                watchThread.interrupt();
            } catch (Exception e) {
                // not caring right now
            }
        }
    }

    private synchronized void resetTrigger(Path dirPath, List<WatchEvent<?>> pollEvents) {
        for (WatchEvent<?> event : pollEvents) {
            Path root = watchedDir;
            if (dirPath != null) {
                root = dirPath;
            }
            Path path = root.resolve((Path) event.context());
            LOGGER.trace("event : " + event.kind() + " : " + path);
            if (StandardWatchEventKinds.ENTRY_CREATE == event.kind()) {
                currentCreated.add(path);
            }
            if (StandardWatchEventKinds.ENTRY_DELETE == event.kind()) {
                currentDeleted.add(path);
            }
            if (StandardWatchEventKinds.ENTRY_MODIFY == event.kind()) {
                currentModified.add(path);
            }
        }

        if (timer != null) {
            timer.cancel();
            timer = null;
        }
        
        currentModified.removeAll(currentCreated);
        ImmutableSet<Path> deleteInstall = Sets.intersection(currentDeleted, currentCreated).immutableCopy();
        currentModified.addAll(deleteInstall);
        currentCreated.removeAll(deleteInstall);
        currentDeleted.removeAll(deleteInstall);
        ImmutableList<Path> created = ImmutableList.copyOf(currentCreated);
        ImmutableList<Path> deleted = ImmutableList.copyOf(currentDeleted);
        ImmutableList<Path> modified = ImmutableList.copyOf(currentModified);
        
        LOGGER.trace("created: " + Joiner.on(",").join(created));
        LOGGER.trace("deleted: "+ Joiner.on(",").join(deleted));
        LOGGER.trace("modified: " + Joiner.on(",").join(modified));
        timer = new Timer("DirWatcher");
        timer.schedule(new TimerTask() {
            @Override
            public void run() {

                if (!created.isEmpty()) {
                    listener.filesCreated(created);
                }
                if (!deleted.isEmpty()) {
                    listener.filesDeleted(deleted);
                }
                if (!modified.isEmpty()) {
                    listener.filesUpdated(modified);
                }
                
                currentCreated.clear();
                currentDeleted.clear();
                currentModified.clear();

                installWatcherRecursively();
                cancelWatchDirs();
            }
        }, triggerTime);
    }

    private synchronized void installWatcherRecursively() {

        try {
            Files.walkFileTree(watchedDir, new FileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    watchDir(dir);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            // Don't care
        }
    }

    private synchronized void cancelWatchDirs() {
        Set<Path> paths = Sets.newHashSet(pathToKeyMap.keySet());

        for (Path path : paths) {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                cancelWatchDir(path);
            }
        }
    }

    private void cancelWatchDir(Path dir) {
        WatchKey watchKey = pathToKeyMap.get(dir);

        if (watchKey != null) {
            watchKey.cancel();
            pathToKeyMap.remove(dir);
        }
    }

    private void watchDir(Path dir) {
        if (!pathToKeyMap.containsKey(dir)) {
            try {
                WatchKey watchKey = dir.register(watchService, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY, OVERFLOW);
                pathToKeyMap.put(dir, watchKey);
            } catch (IOException e) {
                // Don't care
            }
        }
    }

}