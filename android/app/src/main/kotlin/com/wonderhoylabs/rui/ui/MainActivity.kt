package com.wonderhoylabs.rui.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wonderhoylabs.rui.R

class MainActivity : ComponentActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		enableEdgeToEdge()
		super.onCreate(savedInstanceState)
		setContent {
			TemplateTheme {
				Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
					MainScreen()
				}
			}
		}
	}
}

@Composable
fun MainScreen() {
	Column(
		modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 16.dp),
		verticalArrangement = Arrangement.spacedBy(24.dp),
	) {
		BrandHeader(subtitle = stringResource(R.string.subtitle), modifier = Modifier.padding(top = 16.dp))
		Card(
			modifier = Modifier.fillMaxWidth(),
			colors =
				CardDefaults.cardColors(
					containerColor = MaterialTheme.colorScheme.primaryContainer,
					contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
				),
		) {
			Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
				Text(stringResource(R.string.start_here), style = MaterialTheme.typography.bodyLarge)
			}
		}
		Spacer(Modifier.weight(1f))
		BrandFooter()
	}
}
