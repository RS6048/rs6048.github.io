@echo off
rem Java-js Bridge - Windows launcher
rem Required JVM module flags; without them JOGL fails with
rem "com.jogamp.opengl.GLException: Unable to determine GraphicsConfiguration".
rem Extra arguments are forwarded to the jar, e.g.
rem   run.bat -x 1024 -y 768 -html Project.html
rem   run.bat -f -html Project.html
java ^
  --add-exports java.base/java.lang=ALL-UNNAMED ^
  --add-exports java.desktop/sun.awt=ALL-UNNAMED ^
  --add-exports java.desktop/sun.java2d=ALL-UNNAMED ^
  --add-opens java.desktop/sun.awt=ALL-UNNAMED ^
  -jar Java-js_Bridge-1.0-all.jar %*
@pause
