#!/bin/sh
# Java-js Bridge - Linux / macOS launcher
# Required JVM module flags; without them JOGL fails with
# "com.jogamp.opengl.GLException: Unable to determine GraphicsConfiguration".
# Extra arguments are forwarded to the jar, e.g.
#   ./run.sh -x 1024 -y 768 -html Project.html
#   ./run.sh -f -html Project.html
java \
  --add-exports java.base/java.lang=ALL-UNNAMED \
  --add-exports java.desktop/sun.awt=ALL-UNNAMED \
  --add-exports java.desktop/sun.java2d=ALL-UNNAMED \
  --add-opens java.desktop/sun.awt=ALL-UNNAMED \
  -jar Java-js_Bridge-1.0-all.jar "$@"
