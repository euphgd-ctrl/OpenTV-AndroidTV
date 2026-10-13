plugins { id("com.android.application") }
android { namespace="com.eritv.app"; compileSdk=35
 val signingPath = System.getenv("ERITV_KEYSTORE_PATH")
 val signingPassword = System.getenv("ERITV_SIGNING_PASSPHRASE")
 val signingAlias = "eritv"
 val signingKeyPassword = signingPassword
 if (!signingPath.isNullOrBlank() && !signingPassword.isNullOrBlank() && !signingAlias.isNullOrBlank() && !signingKeyPassword.isNullOrBlank()) {
   signingConfigs { create("eritvRelease") {
     storeFile = file(signingPath)
     storePassword = signingPassword
     keyAlias = signingAlias
     keyPassword = signingKeyPassword
   } }
   buildTypes { getByName("debug") { signingConfig = signingConfigs.getByName("eritvRelease") } }
 }

 defaultConfig { applicationId="com.eritv.app"; minSdk=23; targetSdk=35; versionCode=7; versionName="1.3.1" }
 compileOptions { sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 }
}
dependencies {
 implementation("androidx.appcompat:appcompat:1.7.0")
 implementation("androidx.media3:media3-exoplayer:1.5.1")
 implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
 implementation("androidx.media3:media3-ui:1.5.1")
}