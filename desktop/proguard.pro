# Obfuscate the Windows program so the license checks are hard to locate and patch.
-injars ../jar/app.jar
-outjars ../jar/app-obf.jar
-libraryjars <java.home>/jmods/java.base.jmod(!**.jar;!module-info.class)
-libraryjars <java.home>/jmods/java.desktop.jmod(!**.jar;!module-info.class)
-libraryjars <java.home>/jmods/java.prefs.jmod(!**.jar;!module-info.class)
-libraryjars <java.home>/jmods/java.logging.jmod(!**.jar;!module-info.class)
-libraryjars <java.home>/jmods/java.datatransfer.jmod(!**.jar;!module-info.class)

-keep public class com.james.usbshare.desktop.App {
    public static void main(java.lang.String[]);
}
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-dontoptimize
-repackageclasses 'j'
-allowaccessmodification
-renamesourcefileattribute x
-dontnote
-dontwarn
