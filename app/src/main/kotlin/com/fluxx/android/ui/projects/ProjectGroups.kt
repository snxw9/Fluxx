package com.fluxx.android.ui.projects

import com.fluxx.android.ProjectMetadata
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class ProjectSort(val label: String, val shortLabel: String) {
    Modified("Date modified", "Date"), Created("Date created", "Created"), Name("Name (A-Z)", "Name")
}

data class ProjectGroup(val key: String, val label: String, val projects: List<ProjectMetadata>)

fun groupProjects(projects: List<ProjectMetadata>, sort: ProjectSort, zone: ZoneId): List<ProjectGroup> {
    val dateFormat = DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.ENGLISH)
    val ordered = when (sort) {
        ProjectSort.Modified -> projects.sortedWith(compareByDescending<ProjectMetadata> { it.lastModified }.thenBy { it.name })
        ProjectSort.Created -> projects.sortedWith(compareByDescending<ProjectMetadata> { it.createdAt }.thenBy { it.name })
        ProjectSort.Name -> projects.sortedWith(compareBy<ProjectMetadata> { it.name.trim().uppercase(Locale.ROOT) }.thenBy { it.file.path })
    }
    return ordered.groupBy { project ->
        if (sort == ProjectSort.Name) {
            project.name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "#"
        } else {
            Instant.ofEpochMilli(if (sort == ProjectSort.Created) project.createdAt else project.lastModified)
                .atZone(zone).toLocalDate().toString()
        }
    }.map { (key, entries) ->
        ProjectGroup(key, if (sort == ProjectSort.Name) key else java.time.LocalDate.parse(key).format(dateFormat), entries)
    }
}
