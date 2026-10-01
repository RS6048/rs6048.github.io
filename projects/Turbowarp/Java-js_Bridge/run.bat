@echo off
rem Java-js Bridge - Windows launcher
rem Required JVM module flags; without them JOGL fails with
rem "com.jogamp.opengl.GLException: Unable to determine GraphicsConfiguration".
java ^
  --add-exports java.base/java.lang=ALL-UNNAMED ^
  --add-exports java.desktop/sun.awt=ALL-UNNAMED ^
  --add-exports java.desktop/sun.java2d=ALL-UNNAMED ^
  --add-opens java.desktop/sun.awt=ALL-UNNAMED ^
  -jar Java-js_Bridge-1.0-all.jar
@pause
