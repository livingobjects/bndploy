# bndploy
OSGi framework provisioning with one-shot installs and live watch backed by Java NIO WatchService

Features:
* recursive directory installs & watching
* multiple runtime (install first) and application directories
* bundle start after full install
* bundle update backed by FS change events
* ignore if the same bundle is already installed
* optionally fail when certain bundles are duplicated

