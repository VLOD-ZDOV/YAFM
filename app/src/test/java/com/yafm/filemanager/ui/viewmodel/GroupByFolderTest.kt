package com.yafm.filemanager.ui.viewmodel

import com.yafm.filemanager.data.model.FileItem
import org.junit.Assert.assertEquals
import org.junit.Test

class GroupByFolderTest {
    private fun f(path: String) = FileItem(name = path.substringAfterLast('/'), path = path)

    @Test
    fun groupsByFolderInNameOrderKeepingFileOrder() {
        val root = "/storage/emulated/0"
        val items = listOf(
            f("$root/DCIM/Camera/b.jpg"),
            f("$root/Pictures/x.png"),
            f("$root/DCIM/Camera/a.jpg"),
            f("$root/top.jpg"),
            f("$root/download/y.png")
        )
        val groups = FileExplorerViewModel.groupByFolder(items, root)
        assertEquals(listOf("/", "DCIM/Camera", "download", "Pictures"), groups.map { it.first })
        assertEquals(listOf("b.jpg", "a.jpg"), groups[1].second.map { it.name })
    }

    @Test
    fun sortedByDateTheNewestFileBringsItsFolderUp() {
        val root = "/storage/emulated/0"
        val newestFirst = listOf(f("$root/Download/october.mp4"), f("$root/DCIM/Camera/september.mp4"), f("$root/Download/june.mp4"))
        val groups = FileExplorerViewModel.groupByFolder(newestFirst, root, SortMode.DATE_DESC)
        assertEquals(listOf("Download", "DCIM/Camera"), groups.map { it.first })
        assertEquals(listOf("october.mp4", "june.mp4"), groups[0].second.map { it.name })
    }
}
