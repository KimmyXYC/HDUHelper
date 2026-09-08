
# The framework loads this entry point from META-INF/xposed/java_init.list.
-keep class moe.nepnep.hduhelper.data.island.xposed.IslandModule { public <init>(); *; }
-keep class moe.nepnep.hduhelper.data.background.xposed.BackgroundModule { public <init>(); *; }
-dontwarn io.github.libxposed.**
