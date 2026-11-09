plugins {
	id("com.android.library")
}

// Compile-time stand-ins for the Android Virtualization Framework's system API, which isn't in the public SDK.
// The app only compiles against them (compileOnly), and the real classes come from the com.android.virt module on
// the phone. Signatures and constants are copied from framework-virtualization.jar on Android 17.
android {
	namespace = "com.wonderhoylabs.minori.avfstubs"
	compileSdk = 37

	defaultConfig {
		minSdk = 29
	}

	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_17
		targetCompatibility = JavaVersion.VERSION_17
	}
}
