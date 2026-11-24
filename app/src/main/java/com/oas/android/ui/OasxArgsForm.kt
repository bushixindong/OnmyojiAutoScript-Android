// OASX args/index.dart, argument_view.dart & task_parameter_panel.dart adapted for native Android.
package com.oas.android.ui

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oas.android.R
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

class OasxFormModel(
    val rootSchema: JSONObject,
    initialValue: JSONObject,
    val labels: JSONObject
) {
    private val initialSnapshot = JSONObject(initialValue.toString())
    val working: JSONObject = JSONObject(initialValue.toString())
    var version by mutableIntStateOf(0)
        private set
    val dirtyPaths = mutableStateMapOf<String, Boolean>()
    val invalidPaths = mutableStateMapOf<String, String>()

    fun label(key: String): String {
        val translated = labels.optString(key, key)
        return translated.ifBlank { key }
    }

    fun markDirty(path: String) {
        invalidPaths.remove(path)
        dirtyPaths[path] = true
        version++
    }

    fun markInvalid(path: String, reason: String) {
        invalidPaths[path] = reason
        dirtyPaths[path] = true
        version++
    }

    fun discard() {
        val fresh = JSONObject(initialSnapshot.toString())
        val keys = working.keys().asSequence().toList()
        keys.forEach { working.remove(it) }
        fresh.keys().forEach { k -> working.put(k, fresh.get(k)) }
        dirtyPaths.clear()
        invalidPaths.clear()
        version++
    }

    fun resolve(schema: JSONObject): JSONObject {
        if(schema.has("\$ref")) {
            var cur = rootSchema
            val parts = schema.getString("\$ref").substring(2).split("/")
            for(part in parts) {
                cur = cur.getJSONObject(part.replace("~1", "/").replace("~0", "~"))
            }
            return cur
        }
        val anyOf = schema.optJSONArray("anyOf")
        if(anyOf != null) {
            for(i in 0 until anyOf.length()) {
                val item = anyOf.getJSONObject(i)
                if(item.optString("type") != "null") return resolve(item)
            }
        }
        return schema
    }

    fun resolveForData(schema: JSONObject, data: Any?): JSONObject {
        val choices = schema.optJSONArray("anyOf") ?: schema.optJSONArray("oneOf")
        if(choices != null) {
            for(i in 0 until choices.length()) {
                val candidate = resolve(choices.getJSONObject(i))
                val type = candidate.optString("type")
                if(data is JSONObject && type == "object") {
                    val props = candidate.optJSONObject("properties")
                    var fits = true
                    if(props != null) {
                        val names = props.keys()
                        while(names.hasNext()) {
                            val propName = names.next()
                            val prop = props.getJSONObject(propName)
                            if(prop.has("const") && prop.get("const").toString() != data.opt(propName)?.toString()) {
                                fits = false
                            }
                        }
                    }
                    if(fits) return candidate
                }
                if((data is String && type == "string") ||
                    (data is Boolean && type == "boolean") ||
                    (data is Number && (type == "number" || type == "integer")) ||
                    (data is JSONArray && type == "array")
                ) {
                    return candidate
                }
            }
        }
        return resolve(schema)
    }

    fun defaultValue(schema: JSONObject): Any {
        if(schema.optBoolean("x-random-id")) return UUID.randomUUID().toString().replace("-", "")
        if(schema.has("default")) return cloneJson(schema.get("default"))
        val resolved = resolve(schema)
        if(resolved.has("default")) return cloneJson(resolved.get("default"))
        return when(resolved.optString("type")) {
            "object" -> JSONObject()
            "array" -> JSONArray()
            "boolean" -> false
            "integer" -> 0
            "number" -> 0.0
            else -> ""
        }
    }

    private fun cloneJson(value: Any?): Any = when(value) {
        is JSONObject -> JSONObject(value.toString())
        is JSONArray -> JSONArray(value.toString())
        null -> JSONObject.NULL
        else -> value
    }

    fun validatedCopy(): JSONObject {
        if(invalidPaths.isNotEmpty()) {
            throw IllegalArgumentException(invalidPaths.values.first())
        }
        return JSONObject(working.toString())
    }
}

@Composable
fun OasxTaskParameterPanel(
    controller: OasController,
    id: String,
    onBack: () -> Unit,
    showTopTitleBar: Boolean
) {
    val definition = controller.definition
    val task = controller.tasks.find { it.id == id }
    val title = task?.title ?: when(id) { "GlobalGame" -> "游戏设置"; "Script" -> "脚本与调度"; else -> id }
    val form = remember(id, definition) {
        definition?.let {
            try {
                OasxFormModel(it.getJSONObject("schema"), it.getJSONObject("value"), controller.labels)
            } catch(_: Throwable) { null }
        }
    }
    var localError by remember(id, definition) { mutableStateOf("") }
    var showImportDialog by remember { mutableStateOf(false) }
    val scrollTrigger = form?.version ?: 0
    val dirtyCount = remember(form, scrollTrigger) { form?.dirtyPaths?.size ?: 0 }

    fun save(runAfter: Boolean) {
        val currentForm = form ?: return
        try {
            localError = ""
            val payload = currentForm.validatedCopy().toString()
            controller.saveEditor(id, payload, runAfter) {
                currentForm.dirtyPaths.clear()
                if(runAfter) onBack()
            }
        } catch(problem: Throwable) {
            localError = problem.message ?: "配置输入无效"
        }
    }

    Column(Modifier.fillMaxSize()) {
        // OASX TaskParameterPanel header row
        Row(
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = if(showTopTitleBar) 8.dp else 0.dp,
                    vertical = if(showTopTitleBar) 6.dp else 2.dp
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
            }
            Spacer(Modifier.width(4.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if(id != "GlobalGame" && id != "Script") {
                IconButton(onClick = { showImportDialog = true }, enabled = !controller.editorBusy, modifier = Modifier.size(34.dp)) {
                    Icon(painterResource(R.drawable.ic_oasx_download), "导入任务 JSON", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = { controller.exportTaskJson(id, copyOnly = false) }, enabled = !controller.editorBusy, modifier = Modifier.size(34.dp)) {
                    Icon(painterResource(R.drawable.ic_oasx_upload), "导出任务 JSON", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = { controller.exportTaskJson(id, copyOnly = true) }, enabled = !controller.editorBusy, modifier = Modifier.size(34.dp)) {
                    Icon(painterResource(R.drawable.ic_oasx_copy), "复制任务 JSON", modifier = Modifier.size(18.dp))
                }
            }
            if(dirtyCount > 0) {
                TextButton(
                    onClick = { save(false) },
                    enabled = form != null && !controller.editorBusy
                ) { Text("保存") }
            }
            if(task?.runnable == true) {
                FilledTonalButton(
                    onClick = { save(true) },
                    enabled = form != null && !controller.active && !controller.busy && !controller.editorBusy && controller.connected && controller.environmentReady,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("运行此任务")
                }
            }
        }

        val error = localError.ifBlank { controller.editorError }
        if(error.isNotBlank()) {
            Text(
                error,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        if(definition == null && error.isBlank()) {
            Box(Modifier.weight(1f).fillMaxWidth()) { LoadingPanel("正在载入配置…") }
        } else if(form != null) {
            val props = form.rootSchema.optJSONObject("properties")
            val topKeys = remember(id, definition) {
                props?.keys()?.asSequence()?.toList().orEmpty()
            }
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(topKeys, key = { "$id-$it" }) { key ->
                    val propSchema = props!!.getJSONObject(key)
                    val resolved = form.resolveForData(propSchema, form.working.opt(key))
                    if(resolved.optString("type") == "object" && resolved.has("properties")) {
                        OasxGroupCard(
                            model = form,
                            groupKey = key,
                            groupSchema = resolved,
                            data = getOrCreateObject(form.working, key, form, propSchema)
                        )
                    } else {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.24f)
                        ) {
                            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                OasxSchemaField(
                                    model = form,
                                    schema = propSchema,
                                    path = key,
                                    name = key,
                                    currentValue = if(form.working.has(key)) form.working.get(key) else form.defaultValue(propSchema),
                                    onUpdate = { next ->
                                        form.working.put(key, next)
                                        form.markDirty(key)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // OASX ArgsDraftBar when changes are staged, or subtle save prompt
        if(dirtyCount > 0 && form != null) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "待保存更改: $dirtyCount",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    TextButton(onClick = { form.discard() }) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { save(false) },
                        enabled = !controller.editorBusy,
                        shape = RoundedCornerShape(10.dp)
                    ) { Text("保存配置") }
                }
            }
        }
    }

    if(showImportDialog) {
        TaskJsonImportDialog(
            taskTitle = title,
            onPickFile = { onFile -> controller.onPickJsonFile?.invoke { _, text -> onFile(text) } },
            onDismiss = { showImportDialog = false },
            onConfirm = { text ->
                showImportDialog = false
                controller.importTaskJson(id, text)
            }
        )
    }
}

@Composable
private fun TaskJsonImportDialog(
    taskTitle: String,
    onPickFile: ((String) -> Unit) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var jsonText by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入 $taskTitle JSON") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("粘贴任务 JSON 文本，或从文件选择 .json 配置。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = jsonText,
                    onValueChange = { jsonText = it },
                    placeholder = { Text("{\"scheduler\": ...}") },
                    minLines = 3,
                    maxLines = 6,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(
                    onClick = { onPickFile { picked -> onConfirm(picked) } },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(painterResource(R.drawable.ic_oasx_download), null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("选择 JSON 文件")
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(jsonText) }, enabled = jsonText.isNotBlank()) { Text("导入") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun OasxGroupCard(
    model: OasxFormModel,
    groupKey: String,
    groupSchema: JSONObject,
    data: JSONObject
) {
    // OASX ExpansionTile defaults initiallyExpanded = true with secondaryContainer 0.24 alpha & Divider.
    var expanded by remember(groupKey) { mutableStateOf(true) }
    val version = model.version
    val properties = groupSchema.optJSONObject("properties") ?: return
    val memberKeys = remember(groupSchema.toString()) { properties.keys().asSequence().toList() }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.24f)
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    model.label(groupKey),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    painterResource(if(expanded) R.drawable.ic_oasx_expand_less else R.drawable.ic_oasx_expand_more),
                    null,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if(expanded) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    thickness = 0.8.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
                )
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    memberKeys.forEach { memberKey ->
                        val propSchema = properties.getJSONObject(memberKey)
                        val memberPath = "$groupKey.$memberKey"
                        val current = remember(version, memberKey) {
                            if(data.has(memberKey)) data.get(memberKey)
                            else model.defaultValue(propSchema).also { data.put(memberKey, it) }
                        }
                        OasxSchemaField(
                            model = model,
                            schema = propSchema,
                            path = memberPath,
                            name = memberKey,
                            currentValue = current,
                            onUpdate = { next ->
                                data.put(memberKey, next)
                                model.markDirty(memberPath)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OasxSchemaField(
    model: OasxFormModel,
    schema: JSONObject,
    path: String,
    name: String,
    currentValue: Any?,
    onUpdate: (Any) -> Unit
) {
    val version = model.version
    val liveValue = remember(version, currentValue) { currentValue }

    // 1. Handle oneOf multi-variant schemas (e.g. CustomTask entries)
    val oneOf = schema.optJSONArray("oneOf")
    if(oneOf != null && oneOf.length() > 1) {
        val currentObj = liveValue as? JSONObject ?: JSONObject()
        val matched = model.resolveForData(schema, currentObj)
        val titles = (0 until oneOf.length()).map { idx ->
            model.resolve(oneOf.getJSONObject(idx)).optString("title", "选项 ${idx + 1}")
        }
        val selectedIndex = (0 until oneOf.length()).indexOfFirst { idx ->
            model.resolve(oneOf.getJSONObject(idx)) == matched
        }.coerceAtLeast(0)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OasxEnumDropdown(
                selectedLabel = titles[selectedIndex],
                options = titles.mapIndexed { idx, label -> idx.toString() to label },
                onSelect = { chosen ->
                    val idx = chosen.toInt()
                    val fresh = model.defaultValue(oneOf.getJSONObject(idx))
                    onUpdate(fresh)
                }
            )
            OasxSchemaField(
                model = model,
                schema = oneOf.getJSONObject(selectedIndex),
                path = path,
                name = "",
                currentValue = liveValue,
                onUpdate = onUpdate
            )
        }
        return
    }

    // 2. Handle nullable anyOf [T, null]
    val anyOf = schema.optJSONArray("anyOf")
    val isNullable = remember(schema.toString()) {
        anyOf != null && (0 until anyOf.length()).any { anyOf.getJSONObject(it).optString("type") == "null" }
    }
    if(isNullable) {
        val isNull = liveValue == null || liveValue == JSONObject.NULL
        val nonNullSchema = model.resolve(schema)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = isNull,
                    onCheckedChange = { checked ->
                        onUpdate(if(checked) JSONObject.NULL else model.defaultValue(nonNullSchema))
                    }
                )
                Text("${model.label(name)} · 不设置", style = MaterialTheme.typography.bodyMedium)
            }
            if(!isNull) {
                Box(Modifier.padding(start = 12.dp)) {
                    OasxSchemaField(
                        model = model,
                        schema = nonNullSchema,
                        path = path,
                        name = name,
                        currentValue = liveValue,
                        onUpdate = onUpdate
                    )
                }
            }
        }
        return
    }

    val resolved = model.resolveForData(schema, liveValue)
    val inferred = when(liveValue) {
        is JSONObject -> "object"
        is JSONArray -> "array"
        is Boolean -> "boolean"
        is Int, is Long -> "integer"
        is Number -> "number"
        else -> "string"
    }
    val type = resolved.optString("type", inferred)

    if(resolved.has("const")) {
        if(name.isNotEmpty()) {
            OasxArgumentLayout(
                title = model.label(name),
                description = descriptionOf(model, resolved),
                control = {
                    Text(
                        model.label(resolved.get("const").toString()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )
        }
        return
    }

    if(type == "object") {
        val obj = liveValue as? JSONObject ?: JSONObject()
        val props = resolved.optJSONObject("properties")
        if(props != null) {
            val childKeys = remember(resolved.toString()) { props.keys().asSequence().toList() }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if(name.isNotEmpty()) {
                    Text(model.label(name), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                ) {
                    Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        childKeys.forEach { childKey ->
                            val childSchema = props.getJSONObject(childKey)
                            val childPath = "$path.$childKey"
                            val childVal = if(obj.has(childKey)) obj.get(childKey) else model.defaultValue(childSchema)
                            if(!obj.has(childKey)) obj.put(childKey, childVal)
                            OasxSchemaField(
                                model = model,
                                schema = childSchema,
                                path = childPath,
                                name = childKey,
                                currentValue = childVal,
                                onUpdate = { next ->
                                    obj.put(childKey, next)
                                    onUpdate(obj)
                                }
                            )
                        }
                    }
                }
            }
            return
        }
        // Dynamic dictionary (additionalProperties)
        val itemSchema = resolved.optJSONObject("additionalProperties") ?: JSONObject()
        val entries = obj.keys().asSequence().toList()
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if(name.isNotEmpty()) Text(model.label(name), style = MaterialTheme.typography.bodyMedium)
            entries.forEach { entryKey ->
                var keyText by remember(entryKey, version) { mutableStateOf(entryKey) }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = keyText,
                                onValueChange = { nextKey ->
                                    keyText = nextKey
                                    val trimmed = nextKey.trim()
                                    if(trimmed.isNotEmpty() && trimmed != entryKey && !obj.has(trimmed)) {
                                        val v = obj.remove(entryKey)
                                        obj.put(trimmed, v)
                                        onUpdate(obj)
                                    }
                                },
                                label = { Text("键名") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp)
                            )
                            IconButton(onClick = { obj.remove(entryKey); onUpdate(obj) }) {
                                Icon(Icons.Default.Close, "删除条目")
                            }
                        }
                        OasxSchemaField(
                            model = model,
                            schema = itemSchema,
                            path = "$path.$entryKey",
                            name = "",
                            currentValue = obj.opt(entryKey),
                            onUpdate = { nextVal ->
                                obj.put(entryKey, nextVal)
                                onUpdate(obj)
                            }
                        )
                    }
                }
            }
            OutlinedButton(
                onClick = {
                    var idx = entries.size + 1
                    while(obj.has("item_$idx")) idx++
                    obj.put("item_$idx", model.defaultValue(itemSchema))
                    onUpdate(obj)
                },
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("添加条目")
            }
        }
        return
    }

    if(type == "array") {
        val arr = liveValue as? JSONArray ?: JSONArray()
        val itemSchema = resolved.optJSONObject("items")
            ?: resolved.optJSONArray("prefixItems")?.optJSONObject(0)
            ?: JSONObject()
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if(name.isNotEmpty()) {
                Text(model.label(name), style = MaterialTheme.typography.bodyMedium)
            }
            descriptionOf(model, resolved)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            for(i in 0 until arr.length()) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Top) {
                        Box(Modifier.weight(1f)) {
                            OasxSchemaField(
                                model = model,
                                schema = itemSchema,
                                path = "$path[$i]",
                                name = "",
                                currentValue = arr.get(i),
                                onUpdate = { nextVal ->
                                    arr.put(i, nextVal)
                                    onUpdate(arr)
                                }
                            )
                        }
                        IconButton(onClick = {
                            val replacement = JSONArray()
                            for(j in 0 until arr.length()) if(j != i) replacement.put(arr.get(j))
                            onUpdate(replacement)
                        }) {
                            Icon(Icons.Default.Close, "删除这一项")
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = {
                    arr.put(model.defaultValue(itemSchema))
                    onUpdate(arr)
                },
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("添加一项")
            }
        }
        return
    }

    // Scalar leaves rendered with OASX ArgumentView layout (column in portrait, split row in landscape)
    val titleText = if(name.isNotEmpty()) model.label(name) else ""
    val descText = descriptionOf(model, resolved)
    val enumOptions = resolved.optJSONArray("enum")

    OasxArgumentLayout(
        title = titleText,
        description = descText,
        control = {
            when {
                enumOptions != null -> {
                    val options = (0 until enumOptions.length()).map { idx ->
                        val rawOpt = enumOptions.get(idx)
                        rawOpt.toString() to model.label(rawOpt.toString())
                    }
                    val currentStr = liveValue?.toString().orEmpty()
                    val selectedLabel = options.firstOrNull { it.first == currentStr }?.second ?: currentStr
                    OasxEnumDropdown(
                        selectedLabel = selectedLabel,
                        options = options,
                        onSelect = { rawStr ->
                            val typed: Any = (0 until enumOptions.length())
                                .map { enumOptions.get(it) }
                                .firstOrNull { it.toString() == rawStr } ?: rawStr
                            onUpdate(typed)
                        }
                    )
                }
                type == "boolean" -> {
                    Checkbox(
                        checked = liveValue == true,
                        onCheckedChange = { checked -> onUpdate(checked) }
                    )
                }
                resolved.optString("format") == "date-time" || name == "next_run" -> {
                    OasxDateTimeInput(
                        value = liveValue?.toString().orEmpty(),
                        onUpdate = onUpdate
                    )
                }
                else -> {
                    val initialText = if(liveValue == null || liveValue == JSONObject.NULL) "" else liveValue.toString()
                    var text by remember(path, version) { mutableStateOf(initialText) }
                    var inputError by remember(path, version) { mutableStateOf<String?>(null) }
                    val isPassword = name.lowercase().matches(Regex(".*(password|token|secret).*"))
                    val isMultiLine = type == "multi_line"
                    val keyboardType = when(type) {
                        "integer", "number" -> KeyboardType.Number
                        else -> KeyboardType.Text
                    }
                    OutlinedTextField(
                        value = text,
                        onValueChange = { next ->
                            text = next
                            when(type) {
                                "integer" -> {
                                    val parsed = next.trim().toLongOrNull()
                                    if(parsed == null) { inputError = "请输入整数"; model.markInvalid(path, "${model.label(name)} 需为整数") }
                                    else { inputError = null; onUpdate(parsed) }
                                }
                                "number" -> {
                                    val parsed = next.trim().toDoubleOrNull()
                                    if(parsed == null) { inputError = "请输入数字"; model.markInvalid(path, "${model.label(name)} 需为数字") }
                                    else { inputError = null; onUpdate(parsed) }
                                }
                                else -> {
                                    inputError = null
                                    onUpdate(next)
                                }
                            }
                        },
                        isError = inputError != null,
                        supportingText = inputError?.let { msg -> { Text(msg) } },
                        singleLine = !isMultiLine,
                        minLines = if(isMultiLine) 3 else 1,
                        visualTransformation = if(isPassword) PasswordVisualTransformation() else VisualTransformation.None,
                        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    )
}

@Composable
private fun OasxArgumentLayout(
    title: String,
    description: String?,
    control: @Composable () -> Unit
) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    if(landscape && title.isNotEmpty()) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 4.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                if(!description.isNullOrBlank()) {
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f)) { control() }
        }
    } else {
        Column(
            Modifier.fillMaxWidth().padding(bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if(title.isNotEmpty()) Text(title, style = MaterialTheme.typography.bodyMedium)
            if(!description.isNullOrBlank()) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            control()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OasxEnumDropdown(
    selectedLabel: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { (rawValue, label) ->
                DropdownMenuItem(
                    text = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        expanded = false
                        onSelect(rawValue)
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OasxDateTimeInput(value: String, onUpdate: (Any) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    var text by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onUpdate(it) },
        singleLine = true,
        shape = RoundedCornerShape(8.dp),
        trailingIcon = {
            IconButton(onClick = { showPicker = true }) {
                Icon(painterResource(R.drawable.ic_oasx_schedule), "选择日期时间", modifier = Modifier.size(20.dp))
            }
        },
        modifier = Modifier.fillMaxWidth()
    )
    if(showPicker) {
        val initial = remember(text) {
            try { LocalDateTime.parse(text.trim().replace(' ', 'T')) }
            catch(_: Exception) { LocalDateTime.now() }
        }
        var stepTime by remember { mutableStateOf(false) }
        val zone = ZoneId.systemDefault()
        val dateState = rememberDatePickerState(initialSelectedDateMillis = initial.atZone(zone).toInstant().toEpochMilli())
        val timeState = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
        if(!stepTime) {
            DatePickerDialog(
                onDismissRequest = { showPicker = false },
                confirmButton = { TextButton(onClick = { stepTime = true }) { Text("下一步") } },
                dismissButton = { TextButton(onClick = { showPicker = false }) { Text("取消") } }
            ) { DatePicker(state = dateState) }
        } else {
            AlertDialog(
                onDismissRequest = { showPicker = false },
                title = { Text("选择时间") },
                text = { TimePicker(state = timeState) },
                confirmButton = {
                    TextButton(onClick = {
                        val date = dateState.selectedDateMillis?.let {
                            Instant.ofEpochMilli(it).atZone(zone).toLocalDate()
                        } ?: LocalDate.now()
                        val formatted = LocalDateTime.of(date, LocalTime.of(timeState.hour, timeState.minute, 0))
                            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                        text = formatted
                        onUpdate(formatted)
                        showPicker = false
                    }) { Text("确定") }
                },
                dismissButton = { TextButton(onClick = { stepTime = false }) { Text("上一步") } }
            )
        }
    }
}

private fun descriptionOf(model: OasxFormModel, schema: JSONObject): String? {
    val raw = schema.optString("description")
    if(raw.isBlank() || !model.labels.has(raw)) return null
    val translated = model.label(raw).trim()
    return if(translated.isBlank() || translated == raw || translated.endsWith("_help")) null else translated
}

private fun getOrCreateObject(parent: JSONObject, key: String, model: OasxFormModel, schema: JSONObject): JSONObject {
    val existing = parent.opt(key)
    if(existing is JSONObject) return existing
    val created = model.defaultValue(schema) as? JSONObject ?: JSONObject()
    parent.put(key, created)
    return created
}
