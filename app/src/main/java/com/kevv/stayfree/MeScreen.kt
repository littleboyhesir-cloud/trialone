package com.kevv.stayfree

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MeScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val session by vm.session.collectAsState()
    val status by vm.cloudStatus.collectAsState()
    val busy by vm.busy.collectAsState()
    val last by vm.lastBackup.collectAsState()
    val reasons by vm.reasons.collectAsState()
    val hours by vm.notifHours.collectAsState()
    var email by rememberSaveable { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var newReason by remember { mutableStateOf("") }
    val fmt = remember { SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.setNotif(setOf(8, 20))
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ---------- Reasons ----------
        Panel {
            Text("My reasons", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("These appear every time you open the app.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            reasons.forEachIndexed { i, r ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("\u2764\uFE0F  $r", Modifier.weight(1f))
                    TextButton(onClick = { vm.removeReason(i) }) { Text("Delete") }
                }
            }
            OutlinedTextField(value = newReason, onValueChange = { newReason = it },
                label = { Text("e.g. I want to be present with my partner") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { vm.addReason(newReason); newReason = "" }, enabled = newReason.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Add reason") }
        }

        // ---------- Notifications ----------
        Panel {
            Text("Progress notifications", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Daily updates on your streak, milestones and a motivation. The lock screen only shows a neutral message.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Switch(checked = hours.isNotEmpty(), onCheckedChange = { on ->
                    if (!on) vm.setNotif(emptySet())
                    else if (Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else vm.setNotif(setOf(8, 20))
                })
                Spacer(Modifier.width(8.dp))
                Text(if (hours.isNotEmpty()) "On" else "Off")
            }
            if (hours.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Reminders.slots.forEach { (h, label) ->
                        FilterChip(selected = h in hours, onClick = {
                            vm.setNotif(if (h in hours) hours - h else hours + h)
                        }, label = { Text(label) })
                    }
                }
                Text("Times are approximate; Android may deliver a few minutes late to save battery.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = { vm.testNotification() }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text("Send a test notification")
            }
        }

        // ---------- Account ----------
        Panel {
            Text("Account & cloud backup", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            val s = session
            when {
                !Cloud.configured -> Text(
                    "Cloud login isn't set up in this build yet. Follow SETUP_CLOUD.md (about 10 minutes, free), then rebuild the app.",
                    color = MaterialTheme.colorScheme.secondary
                )
                s == null -> {
                    Text("Sign in so your streak, cravings and reasons are saved online. If you lose your phone, sign in on a new one and restore everything.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Email") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    OutlinedTextField(value = pw, onValueChange = { pw = it }, label = { Text("Password (6+ characters)") },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.signIn(email, pw, false) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Sign in") }
                        OutlinedButton(onClick = { vm.signIn(email, pw, true) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Create account") }
                    }
                    TextButton(onClick = { vm.resetPassword(email) }, enabled = !busy) { Text("Forgot password?") }
                }
                else -> {
                    Text("Signed in as ${s.email}", fontWeight = FontWeight.SemiBold)
                    Text(if (last > 0) "Last backup: ${fmt.format(last)}" else "No backup yet",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Changes are backed up automatically a few seconds after you make them.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.backupNow() }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Back up now") }
                        OutlinedButton(onClick = { vm.restoreNow() }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Restore") }
                    }
                    TextButton(onClick = { vm.signOut() }) { Text("Sign out") }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            if (status.isNotBlank()) Text(status, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.primary)
        }

        Text("Your data is private. Use a strong, unique password.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
