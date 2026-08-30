package com.eareyereading.ui.screens.dictionary

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eareyereading.ui.theme.*
import com.eareyereading.util.DictionaryManager
import com.eareyereading.util.DictionaryStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DictionaryUiState(
    val statuses: List<DictionaryStatus> = emptyList(),
    val activeDictId: String? = null,
    val loading: Boolean = false,
    val snackbarMessage: String? = null,
)

@HiltViewModel
class DictionaryViewModel @Inject constructor(
    private val dictionaryManager: DictionaryManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DictionaryUiState())
    val uiState: StateFlow<DictionaryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                dictionaryManager.statuses.collect { statuses ->
                    _uiState.update { it.copy(statuses = statuses) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("DictionaryViewModel", "statuses collect failed", e)
            }
        }
        viewModelScope.launch {
            try {
                dictionaryManager.activeDictId.collect { activeId ->
                    _uiState.update { it.copy(activeDictId = activeId) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("DictionaryViewModel", "activeDictId collect failed", e)
            }
        }
        // 首次进入时刷新词典列表
        refresh()
    }

    fun refresh() {
        if (_uiState.value.loading) return
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(loading = true) }
                val ok = dictionaryManager.refreshManifest()
                _uiState.update {
                    it.copy(
                        loading = false,
                        snackbarMessage = if (!ok && dictionaryManager.manifestError.value != null)
                            dictionaryManager.manifestError.value else null,
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("DictionaryViewModel", "refresh failed", e)
                _uiState.update { it.copy(loading = false) }
            }
        }
    }

    fun download(dictId: String) {
        viewModelScope.launch {
            try {
                val ok = dictionaryManager.download(dictId) { /* 进度通过 statuses flow 更新 */ }
                _uiState.update {
                    it.copy(snackbarMessage = if (ok) "词典下载完成" else "下载失败，请检查网络后重试")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("DictionaryViewModel", "download failed", e)
                _uiState.update { it.copy(snackbarMessage = "下载失败: ${e.javaClass.simpleName}") }
            }
        }
    }

    fun delete(dictId: String) {
        viewModelScope.launch {
            try {
                dictionaryManager.delete(dictId)
                _uiState.update { it.copy(snackbarMessage = "已删除词典") }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("DictionaryViewModel", "delete failed", e)
                _uiState.update { it.copy(snackbarMessage = "删除失败: ${e.javaClass.simpleName}") }
            }
        }
    }

    fun setActive(dictId: String?) {
        dictionaryManager.setActiveDict(dictId)
        _uiState.update { it.copy(snackbarMessage = if (dictId == null) "已切换为内置词典" else "已切换词典") }
    }

    fun dismissSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictionaryManagerScreen(
    onBack: () -> Unit,
    viewModel: DictionaryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(uiState.snackbarMessage) {
        uiState.snackbarMessage?.let {
            scope.launch { snackbarHostState.showSnackbar(it) }
            viewModel.dismissSnackbar()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "词典管理",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, "刷新")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 说明
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "选择适合你水平的词典",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "下载后可离线查词，不依赖网络。可同时保留多个词典，切换使用。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // 内置词典选项
            item {
                val active = uiState.activeDictId == null
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.setActive(null) },
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (active) Primary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface,
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            modifier = Modifier.size(40.dp),
                            shape = RoundedCornerShape(10.dp),
                            color = PrimaryLight,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.MenuBook, null, tint = Primary, modifier = Modifier.size(20.dp))
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "内置词典",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                "约 1200 高频词，已随 App 安装",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (active) {
                            Icon(Icons.Default.CheckCircle, null, tint = Primary, modifier = Modifier.size(24.dp))
                        }
                    }
                }
            }

            // 分级词典列表
            if (uiState.loading && uiState.statuses.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }

            items(uiState.statuses.size) { index ->
                val status = uiState.statuses[index]
                DictionaryCard(
                    status = status,
                    onDownload = { viewModel.download(status.info.id) },
                    onDelete = { viewModel.delete(status.info.id) },
                    onSetActive = { viewModel.setActive(status.info.id) },
                )
            }

            if (uiState.statuses.isEmpty() && !uiState.loading) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "暂无可用词典\n请检查网络后点击右上角刷新",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun DictionaryCard(
    status: DictionaryStatus,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onSetActive: () -> Unit,
) {
    val info = status.info
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (status.active) Primary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = PrimaryLight,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.LibraryBooks, null, tint = Primary, modifier = Modifier.size(20.dp))
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        info.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        info.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (status.active) {
                    Icon(Icons.Default.CheckCircle, null, tint = Primary, modifier = Modifier.size(24.dp))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 状态行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val sizeText = formatBytes(info.sizeBytes)
                Text(
                    "${info.entryCount} 词 · $sizeText",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when {
                    status.downloading -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LinearProgressIndicator(
                                progress = status.progress,
                                modifier = Modifier.width(80.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "${(status.progress * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = Primary,
                            )
                        }
                    }
                    status.downloaded -> {
                        Row {
                            if (!status.active) {
                                TextButton(onClick = onSetActive) { Text("切换") }
                            }
                            TextButton(onClick = onDelete, colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            )) { Text("删除") }
                        }
                    }
                    else -> {
                        Button(onClick = onDownload) {
                            Icon(Icons.Default.Download, null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("下载")
                        }
                    }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.0f KB".format(kb)
    return "%.1f MB".format(kb / 1024.0)
}
