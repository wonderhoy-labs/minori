package com.wonderhoylabs.minori.core

import kotlinx.serialization.json.Json

/** The JSON settings every stored file and native call uses. Unknown keys are kept out, never fatal. */
val MinoriJson =
	Json {
		ignoreUnknownKeys = true
		encodeDefaults = true
		prettyPrint = true
		explicitNulls = false
	}
