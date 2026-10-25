package com.yafm.filemanager.data.fs

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Calls [onFile] for every regular file under [root], with its attributes read by a single lstat
 * (File.walkTopDown() stats each file several times). Links are neither followed nor reported;
 * [enter] decides which folders, [root] included, are walked into. Unreadable folders are skipped.
 */
fun walkFiles(root: String, enter: (Path) -> Boolean = { true }, onFile: (Path, BasicFileAttributes) -> Unit) {
    Files.walkFileTree(Paths.get(root), object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
            if (enter(dir)) FileVisitResult.CONTINUE else FileVisitResult.SKIP_SUBTREE

        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            if (attrs.isRegularFile) onFile(file, attrs)
            return FileVisitResult.CONTINUE
        }

        override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE

        override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult = FileVisitResult.CONTINUE
    })
}

/** A name starting with a dot. */
val Path.isHidden: Boolean
    get() = fileName?.toString()?.startsWith(".") == true
