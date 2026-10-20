package com.example.wifiscanner.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import com.example.wifiscanner.R
import com.example.wifiscanner.model.NetworkDevice
import com.example.wifiscanner.ui.components.DeviceCard
import com.example.wifiscanner.ui.components.EmptyState
import com.example.wifiscanner.ui.matchesQuery

/**
 * Full-screen search. The main screens carry no search bar: this screen owns
 * the search field, focuses it on open, and filters across every visible
 * field of a device (name, IP, MAC, brand, type, service, port, label).
 */
@Composable
fun SearchScreen(
    devices: List<NetworkDevice>,
    onClose: () -> Unit,
    onOpenDevice: (NetworkDevice) -> Unit,
    onEditLocation: (NetworkDevice) -> Unit,
    onEditNickname: (NetworkDevice) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val results = remember(devices, query) { devices.filter { it.matchesQuery(query) } }
    val focusRequester = remember { FocusRequester() }

    // Guarded: requesting focus before the field is attached (slow first
    // frame, screen opened during a transition) would otherwise crash.
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    BackHandler(onBack = onClose)

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.search_close),
                )
            }
            Spacer(Modifier.width(4.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                singleLine = true,
                shape = MaterialTheme.shapes.extraLarge,
                placeholder = { Text(stringResource(R.string.search_hint)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = null,
                    )
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = stringResource(R.string.clear_search),
                            )
                        }
                    }
                },
            )
        }

        Text(
            text = if (query.isBlank() || devices.isEmpty()) {
                stringResource(R.string.search_desc)
            } else {
                pluralStringResource(
                    R.plurals.search_count,
                    devices.size,
                    results.size,
                    devices.size,
                )
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Start,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        )

        if (results.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.Search,
                title = stringResource(
                    if (devices.isEmpty()) R.string.search_no_devices else R.string.search_empty_title
                ),
                message = when {
                    devices.isEmpty() -> stringResource(R.string.dashboard_idle_hint)
                    query.isBlank() -> stringResource(R.string.search_prompt)
                    else -> stringResource(R.string.no_matches)
                },
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(results, key = { it.ip }) { device ->
                    DeviceCard(
                        device = device,
                        onClick = { onOpenDevice(device) },
                        onEditLocation = { onEditLocation(device) },
                        onEditNickname = { onEditNickname(device) },
                    )
                }
            }
        }
    }
}
