plugins {
	id("com.android.application")
	id("org.jetbrains.kotlin.plugin.compose")
	id("org.jetbrains.kotlin.plugin.serialization")
}

// VERSION at the top of the repository is the one version for everything in it.
val appVersion = rootProject.file("../VERSION").readText().trim()
val appVersionCode =
	appVersion.split(".").map(String::toInt).let { (major, minor, patch) ->
		major * 10_000 + minor * 100 + patch
	}

// Pass -Prui.abis=arm64-v8a to build faster for one kind of device. AVF only exists on 64-bit Arm phones, but the
// rest of Rui (images, archives, the Termux backend) works on every ABI.
val abis = (findProperty("rui.abis") as String? ?: "arm64-v8a,armeabi-v7a,x86_64").split(",")

android {
	namespace = "com.wonderhoylabs.rui"
	compileSdk = 37
	ndkVersion = "30.0.16248370"

	defaultConfig {
		applicationId = "com.wonderhoylabs.rui"
		minSdk = 29
		targetSdk = 37
		versionCode = appVersionCode
		versionName = appVersion
		ndk { abiFilters += abis }
		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
	}

	// Set these to sign with your own key. Without them a release APK still installs, because it
	// falls back to the debug key, but Android refuses to update an app across different keys.
	signingConfigs {
		System.getenv("RUI_KEYSTORE")?.let { keystore ->
			create("release") {
				storeFile = file(keystore)
				storePassword = System.getenv("RUI_KEYSTORE_PASSWORD")
				keyAlias = System.getenv("RUI_KEY_ALIAS")
				keyPassword = System.getenv("RUI_KEY_PASSWORD")
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
		aidl = true
		buildConfig = true
		compose = true
	}

	testOptions {
		unitTests.isReturnDefaultValues = true
	}

	sourceSets["main"].jniLibs.directories.add(layout.buildDirectory.dir("rust/jniLibs").get().asFile.path)
	sourceSets["main"].assets.directories.add(layout.buildDirectory.dir("rust/assets").get().asFile.path)
}

// rui-jni holds rui-core's image tools and the pseudo-terminals for local consoles.
val buildRust =
	tasks.register<Exec>("buildRust") {
		val output = layout.buildDirectory.dir("rust/jniLibs")
		val ndk = androidComponents.sdkComponents.ndkDirectory
		workingDir = rootProject.projectDir.parentFile
		inputs.dir(rootProject.file("../crates"))
		inputs.file(rootProject.file("../Cargo.lock"))
		outputs.dir(output)
		doFirst { environment("ANDROID_NDK_HOME", ndk.get().asFile.path) }
		commandLine(
			listOf("cargo", "ndk", "--platform", "29", "-o", output.get().asFile.path) +
				abis.flatMap { listOf("-t", it) } +
				listOf("build", "--release", "-p", "rui-jni"),
		)
	}

// rui-agent runs inside 64-bit Arm Linux guests, so it's built for them and shipped as an asset that first boot
// copies in. It's static and needs no C toolchain.
val buildAgent =
	tasks.register<Exec>("buildAgent") {
		val output = layout.buildDirectory.dir("rust/assets")
		workingDir = rootProject.projectDir.parentFile
		inputs.dir(rootProject.file("../crates/rui-agent"))
		inputs.file(rootProject.file("../Cargo.lock"))
		outputs.dir(output)
		environment("CARGO_TARGET_AARCH64_UNKNOWN_LINUX_MUSL_LINKER", "rust-lld")
		commandLine("cargo", "build", "--release", "-p", "rui-agent", "--target", "aarch64-unknown-linux-musl")
		doLast {
			val agent = rootProject.file("../target/aarch64-unknown-linux-musl/release/rui-agent")
			val dir = output.get().asFile
			dir.mkdirs()
			agent.copyTo(dir.resolve("rui-agent-aarch64"), overwrite = true)
		}
	}

tasks.configureEach {
	if (name.startsWith("merge") && name.endsWith("JniLibFolders")) dependsOn(buildRust)
	if (name.startsWith("merge") && name.endsWith("Assets")) dependsOn(buildAgent)
	if (name.startsWith("generate") && name.endsWith("LintModel")) dependsOn(buildAgent)
	if (name.startsWith("lintAnalyze")) dependsOn(buildAgent)
}

dependencies {
	compileOnly(project(":avf-stubs"))

	implementation(platform("androidx.compose:compose-bom:2026.09.00"))
	implementation("androidx.activity:activity-compose:1.13.0")
	implementation("androidx.compose.foundation:foundation")
	implementation("androidx.compose.material3:material3")
	implementation("androidx.compose.material:material-icons-extended")
	implementation("androidx.compose.ui:ui")
	implementation("androidx.core:core-ktx:1.19.1")
	implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
	implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
	implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
	implementation("dev.rikka.shizuku:api:13.1.5")
	implementation("dev.rikka.shizuku:provider:13.1.5")
	implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.2")
	implementation("com.github.mwiede:jsch:2.28.8")

	testImplementation("junit:junit:4.13.2")
	testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")

	androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
	androidTestImplementation("androidx.compose.ui:ui-test-junit4")
	androidTestImplementation("androidx.test:runner:1.7.0")
	androidTestImplementation("androidx.test.ext:junit:1.3.0")
	debugImplementation("androidx.compose.ui:ui-test-manifest")
}
