package com.kevv.stayfree

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

data class BriefPage(val emoji: String, val title: String, val body: String)

/** Shown every time the app is opened: several motivations before the dashboard. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BriefingScreen(vm: MainViewModel, onDone: () -> Unit) {
    val habit by vm.selected.collectAsState()
    val quote by vm.quote.collectAsState()
    val reasons by vm.reasons.collectAsState()
    val h = habit
    if (h == null) {
        LaunchedEffect(Unit) { onDone() }
        return
    }

    val pages = remember(h.id, h.startTime, quote, reasons) {
        val elapsed = (System.currentTimeMillis() - h.startTime).coerceAtLeast(0)
        val days = elapsed / 86_400_000L
        val ms = milestonesFor(h.category)
        val next = ms.firstOrNull { it.hours * 3_600_000 > elapsed }
        val list = mutableListOf<BriefPage>()

        list += BriefPage(
            "\uD83D\uDD25",
            if (days >= 1) "Day $days" else "You're on your way",
            "You've been free for ${fmtShort(elapsed)}." +
                (if (h.costPerUnit > 0) " Saved so far: ${money(elapsed / 86_400_000.0 * h.unitsPerDay * h.costPerUnit)}." else "") +
                "\nBest streak: ${fmtShort(maxOf(h.bestStreakMs, elapsed))}."
        )
        if (reasons.isEmpty()) {
            list += BriefPage("\u270D\uFE0F", "Why are you doing this?",
                "Open the Me tab and write your own reasons. They'll show up here every time you open the app.")
        } else {
            reasons.shuffled().take(2).forEach { list += BriefPage("\u2764\uFE0F", "Remember why you started", it) }
        }
        quote?.let { list += BriefPage("\uD83D\uDCAC", "Words to carry today", "\u201C${it.first}\u201D\n\n\u2014 ${it.second}") }
        motivations.shuffled().take(2).forEach { list += BriefPage("\uD83D\uDCAA", "Stay strong", it) }
        list += BriefPage("\uD83C\uDFAF", "Today's challenge", challenges.random())
        list += BriefPage(
            "\uD83C\uDFC1", "Next goal",
            next?.let { "${it.title}: in ${fmtShort((it.hours * 3_600_000).toLong() - elapsed)}.\n${it.desc}" }
                ?: "You've reached every milestone. Keep protecting your freedom."
        )
        list
    }

    val pager = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    val last = pager.currentPage >= pages.size - 1

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDone) { Text("Skip") }
        }
        HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth()) { i ->
            val p = pages[i]
            Column(
                Modifier.fillMaxSize().padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(p.emoji, fontSize = 64.sp)
                Spacer(Modifier.height(16.dp))
                Text(p.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(12.dp))
                Text(p.body, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 12.dp)) {
            repeat(pages.size) { i ->
                Box(
                    Modifier.size(if (i == pager.currentPage) 10.dp else 7.dp).background(
                        if (i == pager.currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        CircleShape
                    )
                )
            }
        }
        Button(
            onClick = { if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text(if (last) "Let's go" else "Next") }
    }
}
