# WrtPanel uses kotlinx.serialization only through JsonElement (no reflection-based classes).
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-keepattributes *Annotation*, InnerClasses, Signature
# WorkManager instantiates workers by class name.
-keep class com.the412banner.wrtpanel.update.UpdateWorker { *; }
