# kotlinx.serialization: keep generated serializers' companions.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class com.ideatic.sunwatt.** { kotlinx.serialization.KSerializer serializer(...); }
# OkHttp optional TLS providers.
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
# WorkManager's Room 2.6 rule keeps the database class but not its constructor; R8 full mode
# then removes it and WorkManager crashes at start (seen on a real phone, release build).
-keepclassmembers class * extends androidx.room.RoomDatabase { <init>(); }
