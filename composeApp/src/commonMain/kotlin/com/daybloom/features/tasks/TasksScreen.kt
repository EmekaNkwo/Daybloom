package com.daybloom.features.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daybloom.components.ChoiceChips
import com.daybloom.components.EmptyState
import com.daybloom.components.LoadingState
import com.daybloom.components.MultiChoiceChips
import com.daybloom.components.ScreenHeader
import com.daybloom.components.SectionHeader
import com.daybloom.components.TaskRow
import com.daybloom.design.Spacing
import androidx.compose.ui.text.style.TextOverflow
import com.daybloom.components.listGroupItem
import androidx.compose.foundation.lazy.itemsIndexed
import com.daybloom.domain.models.Priority
import com.daybloom.domain.models.TaskCategory
import org.koin.compose.viewmodel.koinViewModel
import com.daybloom.components.BloomTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.platform.LocalFocusManager

@Composable
fun TasksScreen(
    contentPadding: PaddingValues,
    onOpenTask: (String) -> Unit,
    viewModel: TasksViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val today = state.today
    if (state.loading || today == null) {
        LoadingState(Modifier.padding(contentPadding))
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding() + Spacing.fabClearance),
    ) {
        item {
            Column(Modifier.padding(horizontal = Spacing.screenGutter), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                ScreenHeader("Tasks")
                BloomTextField(
                    value = viewModel.query,
                    onValueChange = viewModel::onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search tasks, notes and tags", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (viewModel.query.isNotEmpty()) IconButton(onClick = { viewModel.onQueryChange("") }) { Icon(Icons.Filled.Clear, "Clear search") }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    // Results filter as you type, so the Search key just puts the keyboard away.
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                    shape = MaterialTheme.shapes.large,
                )
            }
        }
        item {
            // Scrollable so every label stays on one line at any width or font scale.
            PrimaryScrollableTabRow(
                selectedTabIndex = state.status.ordinal,
                modifier = Modifier.padding(top = Spacing.md),
                edgePadding = Spacing.sm,
                containerColor = MaterialTheme.colorScheme.background,
            ) {
                StatusFilter.entries.forEach { s ->
                    Tab(selected = s == state.status, onClick = { viewModel.setStatus(s) }, text = { Text(s.label, maxLines = 1) })
                }
            }
        }
        item {
            Column(Modifier.padding(top = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                // Full-bleed rows: chips scroll to the screen edge but rest aligned with the gutter.
                val gutter = PaddingValues(horizontal = Spacing.screenGutter)
                ChoiceChips(DateFilter.entries, state.dateFilter, { it.label }, viewModel::setDateFilter, contentPadding = gutter)
                MultiChoiceChips(TaskCategory.entries, state.categories, { "${it.emoji} ${it.label}" }, viewModel::toggleCategory, contentPadding = gutter)
                MultiChoiceChips(Priority.entries, state.priorities, { it.label }, viewModel::togglePriority, contentPadding = gutter)
                if (state.hasFilters) {
                    TextButton(onClick = viewModel::clearFilters, modifier = Modifier.padding(start = Spacing.xs)) {
                        Text("Clear filters · ${state.totalMatches} match${if (state.totalMatches == 1) "" else "es"}")
                    }
                }
            }
        }
        if (state.sections.isEmpty()) {
            item {
                EmptyState(
                    emoji = if (state.hasFilters) "🔍" else "🌿",
                    title = if (state.hasFilters) "No matching tasks" else "Nothing here yet",
                    message = if (state.hasFilters) "Try a different search or clear the filters." else "Tap + to add your first task.",
                )
            }
        }
        state.sections.forEach { section ->
            item(key = "header-${section.title}") {
                SectionHeader("${section.title} · ${section.tasks.size}", Modifier.padding(horizontal = Spacing.screenGutter))
            }
            itemsIndexed(section.tasks, key = { _, it -> it.id }) { i, task ->
                TaskRow(
                    task = task,
                    today = today,
                    onToggle = { viewModel.toggle(task, it) },
                    onClick = { onOpenTask(task.id) },
                    modifier = Modifier.listGroupItem(i, section.tasks.size).padding(start = Spacing.xs),
                    showDate = section.title != "Today",
                )
            }
        }
    }
}
