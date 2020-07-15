package com.jetbrains.pluginverifier.misc

import com.jetbrains.plugin.structure.base.utils.createDir
import com.jetbrains.plugin.structure.base.utils.listFiles
import com.jetbrains.plugin.structure.base.utils.readText
import com.jetbrains.plugin.structure.base.utils.writeText
import com.jetbrains.pluginverifier.ide.IdeDownloader.Companion.stripTopLevelDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Path

class StripTopLevelDirectoryTest {

  @JvmField
  @Rule
  val tempFolder = TemporaryFolder()

  /**
   * f/
   */
  @Test
  fun `empty directory`() {
    val f = tempFolder.newFolder().toPath()
    stripTopLevelDirectory(f)
    assertEquals(emptyList<Path>(), f.listFiles())
  }

  /**
   * f/
   *   empty/
   */
  @Test
  fun singleEmptyDirectory() {
    val f = tempFolder.newFolder().toPath()
    f.resolve("empty").createDir()
    stripTopLevelDirectory(f)
    assertTrue(f.listFiles().isEmpty())
  }

  /**
   * f/
   *   a/
   *   b/
   */
  @Test
  fun `more than one file means no change`() {
    val f = tempFolder.newFolder().toPath()
    f.resolve("a").createDir()
    f.resolve("b").createDir()
    stripTopLevelDirectory(f)
    assertEquals(
      listOf(f.resolve("a"), f.resolve("b")),
      f.listFiles().orEmpty().sorted()
    )
  }

  /**
   * f/
   *   s/
   *     a/
   *     b (42)
   *
   * should be
   * f/
   *   a/
   *   b (42)
   */
  @Test
  fun stripped() {
    val f = tempFolder.newFolder().toPath()
    val s = f.resolve("s").createDir()
    s.resolve("a").createDir()
    s.resolve("b").writeText("42")
    stripTopLevelDirectory(f)
    assertEquals(
      listOf(f.resolve("a"), f.resolve("b")),
      f.listFiles().sorted()
    )
    assertEquals("42", f.resolve("b").readText())
  }


  /**
   * f/
   *   s/
   *     a/
   *     s/b.txt  <- "s" in "s" is conflict, which must be handled.
   *
   * should be
   * f/
   *   a/
   *   s/b.txt
   */
  @Test
  fun conflict() {
    val f = tempFolder.newFolder().toPath()
    val s = f.resolve("s").createDir()
    s.resolve("a").createDir()

    val ss = s.resolve("s").createDir()
    ss.resolve("b.txt").writeText("42")

    stripTopLevelDirectory(f)
    assertEquals(
      listOf(f.resolve("a"), f.resolve("s")),
      f.listFiles().sorted()
    )

    assertEquals("42", f.resolve("s").resolve("b.txt").readText())
  }

}