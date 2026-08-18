# Compose + Kotlin reflection-free app: the defaults cover almost everything.
# Keep kotlinx.serialization generated serializers, which are looked up reflectively.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.jmail.**$$serializer { *; }
-keepclassmembers class com.jmail.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# Ktor's OkHttp engine references optional Conscrypt/BouncyCastle providers.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.slf4j.**

# Tink, pulled in by androidx.security.crypto for the encrypted token store, is compiled
# against Error Prone's annotations. They are build-time only and never reach the APK.
-dontwarn com.google.errorprone.annotations.**
