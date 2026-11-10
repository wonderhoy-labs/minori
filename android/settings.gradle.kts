pluginManagement {
	repositories {
		google()
		mavenCentral()
		gradlePluginPortal()
	}
}

dependencyResolutionManagement {
	repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
	repositories {
		google()
		mavenCentral()
		// Wonderhoy Labs libraries, straight from each project's GitHub releases.
		ivy {
			url = uri("https://github.com/wonderhoy-labs")
			patternLayout { artifact("[organisation]/releases/download/v[revision]/[organisation]-[module]-[revision].[ext]") }
			metadataSources { artifact() }
			content { includeGroup("airi") }
		}
	}
}

rootProject.name = "minori"
include(":app", ":avf-stubs")
