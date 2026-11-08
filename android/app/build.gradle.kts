plugins {
	id("com.android.application")
	id("org.jetbrains.kotlin.plugin.compose")
}

// VERSION at the top of the repository is the one version for everything in it.
val appVersion = rootProject.file("../VERSION").readText().trim()
val appVersionCode =
	appVersion.split(".").map(String::toInt).let { (major, minor, patch) ->
		major * 10_000 + minor * 100 + patch
	}

android {
	namespace = "com.wonderhoylabs.template"
	compileSdk = 37

	defaultConfig {
		applicationId = "com.wonderhoylabs.template"
		minSdk = 29
		targetSdk = 37
		versionCode = appVersionCode
		versionName = appVersion
	}

	// Set these to sign with your own key. Without them a release APK still installs, because it
	// falls back to the debug key, but Android refuses to update an app across different keys.
	signingConfigs {
		System.getenv("TEMPLATE_KEYSTORE")?.let { keystore ->
			create("release") {
				storeFile = file(keystore)
				storePassword = System.getenv("TEMPLATE_KEYSTORE_PASSWORD")
				keyAlias = System.getenv("TEMPLATE_KEY_ALIAS")
				keyPassword = System.getenv("TEMPLATE_KEY_PASSWORD")
			}
		}
	}

	buildTypes {
		release {
			isMinifyEnabled = true
			isShrinkResources = true
			proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
			signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
		}
	}

	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_17
		targetCompatibility = JavaVersion.VERSION_17
	}

	buildFeatures {
		compose = true
	}
}

dependencies {
	implementation(platform("androidx.compose:compose-bom:2026.09.00"))
	implementation("androidx.activity:activity-compose:1.13.0")
	implementation("androidx.compose.foundation:foundation")
	implementation("androidx.compose.material3:material3")
	implementation("androidx.compose.ui:ui")

	testImplementation("junit:junit:4.13.2")
}
