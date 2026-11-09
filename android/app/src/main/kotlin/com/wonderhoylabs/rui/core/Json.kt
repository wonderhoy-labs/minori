package com.wonderhoylabs.rui.core

import kotlinx.serialization.json.Json

/** The JSON settings every stored file and native call uses. Unknown keys are kept out, never fatal. */
val RuiJson =
	Json {
		ignoreUnknownKeys = true
		encodeDefaults = true
		prettyPrint = true
		explicitNulls = false
	}
