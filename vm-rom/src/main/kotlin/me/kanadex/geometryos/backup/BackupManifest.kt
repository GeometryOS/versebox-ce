// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
package me.kanadex.geometryos.backup

import java.util.UUID
import me.kanadex.geometryos.rom.RomKeys
import me.kanadex.geometryos.rom.RomSource

/*
 * The backup header carries portable settings and describes the payloads in the ZIP.
 * Its schema has no fields for host paths or the original instance directory ID.
 */
data class BackupManifest(
    val id: UUID,
    val createdAtMillis: Long,
    val settings: BackupVmSettings,
    val client: BackupClient,
    val inventory: BackupBlob,
    val romArchive: BackupBlob? = null,
) {
    init {
        require(createdAtMillis >= 0)
        require(romArchive == null || settings.rom.payloadSha256.isNotEmpty())
    }
}

data class BackupVmSettings(val name: String, val displayMode: String, val rom: BackupRomSelection) {
    init { require(name.isNotBlank()) }
}

data class BackupRomSelection(
    val id: String,
    val version: String,
    val payloadSha256: String,
    val source: String,
    val name: String,
) {
    val key: String get() = RomKeys.key(id, version)

    init {
        require(RomKeys.isValidId(id))
        require(version == RomKeys.LEGACY_VERSION || RomKeys.isValidVersion(version))
        require(payloadSha256.isEmpty() || BACKUP_SHA256.matches(payloadSha256))
        require(source.isEmpty() || RomSource.entries.any { it.name == source })
    }
}

data class BackupClient(val applicationId: String, val versionName: String, val versionCode: Long) {
    init { require(applicationId.isNotBlank() && versionCode >= 0) }
}

data class BackupBlob(val bytes: Long, val sha256: String) {
    init { require(bytes >= 0 && BACKUP_SHA256.matches(sha256)) }
}

internal val BACKUP_SHA256 = Regex("[a-f0-9]{64}")

// This YAML profile accepts one mapping of quoted scalars and comments.
internal object BackupManifestYaml {
    const val HEADER = "backup.yaml"
    const val INVENTORY = "instance/files.tsv"
    const val ROM = "rom/package.zip"
    const val SCHEMA = "versebox-backup/1"
    const val MAX_BYTES = 64 * 1024

    fun encode(value: BackupManifest): ByteArray = buildString {
        fun field(key: String, value: Any) {
            val scalar = value.toString()
            require(scalar.none(Char::isISOControl)) { "Unsupported control character in $key" }
            append(key).append(": \"")
            for (char in scalar) {
                if (char == '\\' || char == '"') append('\\')
                append(char)
            }
            appendLine('"')
        }
        appendLine("# Backup identity and format")
        field("schema", SCHEMA)
        field("backupId", value.id)
        field("createdAtMillis", value.createdAtMillis)
        appendLine("\n# Virtual machine settings")
        field("name", value.settings.name)
        field("displayMode", value.settings.displayMode)
        appendLine("\n# Selected ROM and optional original package")
        with(value.settings.rom) {
            field("romId", id)
            field("romVersion", version)
            field("romPayloadSha256", payloadSha256)
            field("romSource", source)
            field("romName", name)
        }
        value.romArchive?.let {
            field("romArchive", ROM)
            field("romArchiveBytes", it.bytes)
            field("romArchiveSha256", it.sha256)
        }
        appendLine("\n# Instance file inventory")
        field("inventory", INVENTORY)
        field("inventoryBytes", value.inventory.bytes)
        field("inventorySha256", value.inventory.sha256)
        appendLine("\n# Client that created this backup")
        field("clientApplicationId", value.client.applicationId)
        field("clientVersionName", value.client.versionName)
        field("clientVersionCode", value.client.versionCode)
    }.toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_BYTES) }

    fun decode(bytes: ByteArray): BackupManifest {
        require(bytes.size <= MAX_BYTES)
        val fields = mutableMapOf<String, String>()
        for (line in Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString().lineSequence()) {
            if (line.isBlank() || line.startsWith('#')) continue
            val key = line.substringBefore(": ")
            require(key.matches(Regex("[a-zA-Z][a-zA-Z0-9]*")) && line.startsWith("$key: "))
            val raw = line.substring(key.length + 2)
            require(raw.length >= 2 && raw.first() == '"' && raw.last() == '"')
            val decoded = buildString {
                var offset = 1
                while (offset < raw.lastIndex) {
                    val char = raw[offset++]
                    if (char == '\\') {
                        require(offset < raw.lastIndex && (raw[offset] == '\\' || raw[offset] == '"'))
                        append(raw[offset++])
                    } else {
                        require(char != '"' && !char.isISOControl())
                        append(char)
                    }
                }
            }
            require(fields.put(key, decoded) == null) { "Duplicate backup field: $key" }
        }
        fun take(key: String): String = requireNotNull(fields.remove(key)) { "Missing backup field: $key" }
        fun number(key: String): Long = take(key).toLong()
        require(take("schema") == SCHEMA) { "Unsupported backup schema" }
        require(take("inventory") == INVENTORY)
        val id = take("backupId").let { text ->
            UUID.fromString(text).also { require(it.toString() == text) }
        }
        val result = BackupManifest(
            id = id,
            // Schema 1 archives may predate this field. Zero represents an unknown time.
            createdAtMillis = fields.remove("createdAtMillis")?.toLong() ?: 0L,
            settings = BackupVmSettings(
                take("name"), take("displayMode"),
                BackupRomSelection(take("romId"), take("romVersion"), take("romPayloadSha256"),
                    take("romSource"), take("romName")),
            ),
            client = BackupClient(take("clientApplicationId"), take("clientVersionName"), number("clientVersionCode")),
            inventory = BackupBlob(number("inventoryBytes"), take("inventorySha256")),
            romArchive = fields.remove("romArchive")?.let {
                require(it == ROM)
                BackupBlob(number("romArchiveBytes"), take("romArchiveSha256"))
            },
        )
        require(fields.isEmpty()) { "Unknown backup fields: "+ fields.keys.joinToString() }
        return result
    }
}
