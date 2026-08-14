package com.chunland.app.feature.merchant

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chunland.app.core.AppGraph
import com.chunland.app.core.network.apiCall
import com.chunland.app.core.network.apiCallUnit
import com.chunland.app.core.network.userMessage
import com.chunland.app.data.model.AddCategoryRequest
import com.chunland.app.data.model.CategoryScheme
import com.chunland.app.data.model.CreateSchemeRequest
import com.chunland.app.data.model.MerchantProduct
import com.chunland.app.data.model.NameRequest
import com.chunland.app.data.model.SchemeCategory
import com.chunland.app.data.model.SetCategoryProductsRequest
import com.chunland.app.data.model.UpdateSchemeRequest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 分类方案管理（lens 商家侧，对齐 iOS SchemeManageView）：
 * 「方案 = 观察商品的一个视角」，一店多方案；消费者进店 lens 只见 isVisible 方案。
 * 方案：建/改名/显隐/设默认/删（级联）；分类：加/改名/删/归类商品（整体替换语义）。
 * AI 生成方案（origin=ai 隐藏态落库）后置 —— 本页手动路径全量。
 */
@Composable
fun SchemeManageScreen(graph: AppGraph, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var schemes by remember { mutableStateOf<List<CategoryScheme>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }

    // 各类编辑目标（互斥弹层）
    var showCreateScheme by remember { mutableStateOf(false) }
    var renameScheme by remember { mutableStateOf<CategoryScheme?>(null) }
    var deleteScheme by remember { mutableStateOf<CategoryScheme?>(null) }
    var addCategoryTo by remember { mutableStateOf<CategoryScheme?>(null) }
    // 加子分类：需要方案 + 父分类两个坐标
    var addSubTo by remember { mutableStateOf<Pair<CategoryScheme, SchemeCategory>?>(null) }
    var renameCategory by remember { mutableStateOf<SchemeCategory?>(null) }
    var deleteCategory by remember { mutableStateOf<SchemeCategory?>(null) }
    var assignCategory by remember { mutableStateOf<SchemeCategory?>(null) }
    // 移动/改级：方案 + 被移动分类 + 它当前的父（null = 它本身是一级）
    var moveCategory by remember { mutableStateOf<MoveState?>(null) }

    fun toast(msg: String) = scope.launch { snackbar.showSnackbar(msg) }

    LaunchedEffect(refreshKey) {
        error = null
        try {
            schemes = apiCall { graph.merchantConsoleApi.schemes() }.items
        } catch (e: Exception) {
            error = e.userMessage
        }
    }

    Scaffold(
        topBar = {
            Surface {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(4.dp),
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                    Text("分类方案", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    // ✨ AI 起草分类（对齐 iOS AI 起草语义：模型建方案/归类，HITL 确认后落库）
                    IconButton(onClick = { showCreateScheme = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "建方案")
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val list = schemes
        when {
            list == null && error == null -> Box(
                Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            error != null && list == null -> Box(
                Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center,
            ) { Text(error!!, color = MaterialTheme.colorScheme.error) }
            list!!.isEmpty() -> Box(
                Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center,
            ) {
                Text(
                    "还没有分类方案，点右上角 ➕ 建一个\n（方案 = 给顾客的一种浏览视角）",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(list, key = { it.id }) { scheme ->
                    SchemeCard(
                        scheme = scheme,
                        onRename = { renameScheme = scheme },
                        onToggleVisible = {
                            scope.launch {
                                runCatching {
                                    apiCall {
                                        graph.merchantConsoleApi.updateScheme(
                                            scheme.id,
                                            UpdateSchemeRequest(isVisible = !(scheme.isVisible ?: true)),
                                        )
                                    }
                                }.onSuccess { refreshKey++ }
                                    .onFailure { toast(it.userMessage) }
                            }
                        },
                        onSetDefault = {
                            scope.launch {
                                runCatching {
                                    apiCall {
                                        graph.merchantConsoleApi.updateScheme(
                                            scheme.id, UpdateSchemeRequest(isDefault = true),
                                        )
                                    }
                                }.onSuccess { refreshKey++ }
                                    .onFailure { toast(it.userMessage) }
                            }
                        },
                        onDelete = { deleteScheme = scheme },
                        onAddCategory = { addCategoryTo = scheme },
                        onAddSub = { addSubTo = scheme to it },
                        onMove = { cat, parentId -> moveCategory = MoveState(scheme, cat, parentId) },
                        onRenameCategory = { renameCategory = it },
                        onDeleteCategory = { deleteCategory = it },
                        onAssignCategory = { assignCategory = it },
                    )
                }
            }
        }
    }

    // 建方案 / 方案改名 / 分类改名 / 加分类：共用单输入弹窗
    if (showCreateScheme) {
        NameInputDialog(
            title = "新建方案",
            placeholder = "方案名（≤20 字）",
            onDismiss = { showCreateScheme = false },
            onConfirm = { name ->
                scope.launch {
                    runCatching {
                        apiCall { graph.merchantConsoleApi.createScheme(CreateSchemeRequest(name)) }
                    }.onSuccess { showCreateScheme = false; refreshKey++ }
                        .onFailure { toast(it.userMessage) }
                }
            },
        )
    }
    renameScheme?.let { target ->
        NameInputDialog(
            title = "方案改名",
            initial = target.name,
            placeholder = "方案名（≤20 字）",
            onDismiss = { renameScheme = null },
            onConfirm = { name ->
                scope.launch {
                    runCatching {
                        apiCall { graph.merchantConsoleApi.updateScheme(target.id, UpdateSchemeRequest(name = name)) }
                    }.onSuccess { renameScheme = null; refreshKey++ }
                        .onFailure { toast(it.userMessage) }
                }
            },
        )
    }
    addCategoryTo?.let { target ->
        NameInputDialog(
            title = "「${target.name}」加分类",
            placeholder = "分类名（≤20 字）",
            onDismiss = { addCategoryTo = null },
            onConfirm = { name ->
                scope.launch {
                    runCatching {
                        apiCall { graph.merchantConsoleApi.addSchemeCategory(target.id, AddCategoryRequest(name)) }
                    }.onSuccess { addCategoryTo = null; refreshKey++ }
                        .onFailure { toast(it.userMessage) }
                }
            },
        )
    }
    addSubTo?.let { (scheme, parent) ->
        NameInputDialog(
            title = "在「${parent.name}」下加子分类",
            placeholder = "分类名（≤20 字）",
            onDismiss = { addSubTo = null },
            onConfirm = { name ->
                scope.launch {
                    runCatching {
                        apiCall {
                            graph.merchantConsoleApi.addSchemeCategory(
                                scheme.id, AddCategoryRequest(name, parentId = parent.id),
                            )
                        }
                    }.onSuccess { addSubTo = null; refreshKey++ }
                        .onFailure { toast(it.userMessage) }
                }
            },
        )
    }
    renameCategory?.let { target ->
        NameInputDialog(
            title = "分类改名",
            initial = target.name,
            placeholder = "分类名（≤20 字）",
            onDismiss = { renameCategory = null },
            onConfirm = { name ->
                scope.launch {
                    runCatching {
                        apiCallUnit { graph.merchantConsoleApi.renameSchemeCategory(target.id, NameRequest(name)) }
                    }.onSuccess { renameCategory = null; refreshKey++ }
                        .onFailure { toast(it.userMessage) }
                }
            },
        )
    }

    deleteScheme?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteScheme = null },
            title = { Text("删除方案「${target.name}」？") },
            text = { Text("方案下的分类与商品归类将一并删除，顾客进店不再看到此视角。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        runCatching { apiCallUnit { graph.merchantConsoleApi.deleteScheme(target.id) } }
                            .onSuccess { deleteScheme = null; refreshKey++ }
                            .onFailure { toast(it.userMessage) }
                    }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteScheme = null }) { Text("再想想") } },
        )
    }
    deleteCategory?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteCategory = null },
            title = { Text("删除分类「${target.name}」？") },
            text = { Text("该分类下的商品归类将一并删除。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        runCatching { apiCallUnit { graph.merchantConsoleApi.deleteSchemeCategory(target.id) } }
                            .onSuccess { deleteCategory = null; refreshKey++ }
                            .onFailure { toast(it.userMessage) }
                    }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteCategory = null }) { Text("再想想") } },
        )
    }

    assignCategory?.let { target ->
        AssignProductsSheet(
            graph = graph,
            category = target,
            onDismiss = { assignCategory = null },
            onSaved = { assignCategory = null; refreshKey++ },
        )
    }
    moveCategory?.let { st ->
        // parentId=null → 升为一级；数字 → 挂到该一级下。恒发 parentId（含 JsonNull）
        fun doMove(parentId: Int?) {
            scope.launch {
                val body = buildJsonObject {
                    if (parentId == null) put("parentId", JsonNull) else put("parentId", parentId)
                }
                runCatching { apiCallUnit { graph.merchantConsoleApi.moveSchemeCategory(st.cat.id, body) } }
                    .onSuccess { moveCategory = null; refreshKey++ }
                    .onFailure { toast(it.userMessage) }
            }
        }
        AlertDialog(
            onDismissRequest = { moveCategory = null },
            title = { Text("移动「${st.cat.name}」") },
            text = {
                Column {
                    if (st.parentId != null) {
                        TextButton(onClick = { doMove(null) }) { Text("升为一级分类") }
                    }
                    st.scheme.categories
                        .filter { it.id != st.cat.id && it.id != st.parentId }
                        .forEach { top -> TextButton(onClick = { doMove(top.id) }) { Text("移到「${top.name}」下") } }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { moveCategory = null }) { Text("取消") } },
        )
    }
}

/** 移动/改级弹层坐标：方案（取一级目标集）+ 被移动分类 + 当前父 id（null=一级） */
private data class MoveState(val scheme: CategoryScheme, val cat: SchemeCategory, val parentId: Int?)

@Composable
private fun SchemeCard(
    scheme: CategoryScheme,
    onRename: () -> Unit,
    onToggleVisible: () -> Unit,
    onSetDefault: () -> Unit,
    onDelete: () -> Unit,
    onAddCategory: () -> Unit,
    onAddSub: (SchemeCategory) -> Unit,
    onMove: (SchemeCategory, Int?) -> Unit,   // (被移动分类, 当前父 id：null=一级)
    onRenameCategory: (SchemeCategory) -> Unit,
    onDeleteCategory: (SchemeCategory) -> Unit,
    onAssignCategory: (SchemeCategory) -> Unit,
) {
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(scheme.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (scheme.origin == "ai") Tag("AI")
                if (scheme.isDefault) Tag("默认")
                if (scheme.isVisible == false) Tag("隐藏")
                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Filled.DeleteOutline, contentDescription = "删除方案",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onRename) { Text("改名") }
                TextButton(onClick = onToggleVisible) {
                    Text(if (scheme.isVisible == false) "设为可见" else "隐藏")
                }
                if (!scheme.isDefault) {
                    TextButton(onClick = onSetDefault) { Text("设默认") }
                }
            }
            HorizontalDivider()
            if (scheme.categories.isEmpty()) {
                Text(
                    "还没有分类",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                scheme.categories.forEach { cat ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${cat.name}（${cat.productCount ?: 0}）",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { onAddSub(cat) }) { Text("＋子类") }
                        TextButton(onClick = { onAssignCategory(cat) }) { Text("归类") }
                        TextButton(onClick = { onRenameCategory(cat) }) { Text("改名") }
                        // 带子分类的一级不能降为二级（会出三级），只在无子分类时给「移」
                        if (cat.children.isEmpty()) {
                            TextButton(onClick = { onMove(cat, null) }) { Text("移") }
                        }
                        TextButton(onClick = { onDeleteCategory(cat) }) { Text("删") }
                    }
                    // 二级分类：缩进行（删除连带其商品归属；归类/改名与一级同口径）
                    cat.children.forEach { sub ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 16.dp),
                        ) {
                            Text(
                                "└ ${sub.name}（${sub.productCount ?: 0}）",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { onAssignCategory(sub) }) { Text("归类") }
                            TextButton(onClick = { onRenameCategory(sub) }) { Text("改名") }
                            TextButton(onClick = { onMove(sub, cat.id) }) { Text("移") }
                            TextButton(onClick = { onDeleteCategory(sub) }) { Text("删") }
                        }
                    }
                }
            }
            TextButton(onClick = onAddCategory) { Text("＋ 加分类") }
        }
    }
}

@Composable
private fun Tag(text: String) {
    AssistChip(onClick = {}, enabled = false, label = { Text(text) }, modifier = Modifier.padding(end = 4.dp))
}

/** 单行文本输入弹窗（建方案/改名/加分类共用） */
@Composable
private fun NameInputDialog(
    title: String,
    placeholder: String,
    initial: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                placeholder = { Text(placeholder) }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text.trim()) },
                enabled = text.isNotBlank(),
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 分类归类编辑：全店商品 checkbox 多选，保存 = 整体替换（编辑器语义） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AssignProductsSheet(
    graph: AppGraph,
    category: SchemeCategory,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var products by remember { mutableStateOf<List<MerchantProduct>?>(null) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(category.id) {
        try {
            val all = apiCall { graph.merchantConsoleApi.products() }.items
            val assigned = apiCall { graph.merchantConsoleApi.categoryProducts(category.id) }.productCodes
            products = all
            selected = assigned.toSet()
        } catch (e: Exception) {
            error = e.userMessage
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("「${category.name}」归类商品", style = MaterialTheme.typography.titleMedium)
            Text(
                "勾选进入该分类的商品，保存后整体替换原有归类。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val list = products
            when {
                list == null && error == null -> Box(
                    Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
                error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                list!!.isEmpty() -> Text("店内还没有商品", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> LazyColumn(
                    modifier = Modifier.heightIn(max = 400.dp),
                ) {
                    items(list, key = { it.code }) { p ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selected = if (p.code in selected) selected - p.code else selected + p.code
                                },
                        ) {
                            Checkbox(
                                checked = p.code in selected,
                                onCheckedChange = { checked ->
                                    selected = if (checked) selected + p.code else selected - p.code
                                },
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(p.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        }
                    }
                }
            }
            Button(
                onClick = {
                    scope.launch {
                        busy = true
                        runCatching {
                            apiCall {
                                graph.merchantConsoleApi.setCategoryProducts(
                                    category.id, SetCategoryProductsRequest(selected.toList()),
                                )
                            }
                        }.onSuccess { onSaved() }
                            .onFailure { error = it.userMessage }
                        busy = false
                    }
                },
                enabled = !busy && products != null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (busy) "保存中…" else "保存（已选 ${selected.size}）") }
        }
    }
}
