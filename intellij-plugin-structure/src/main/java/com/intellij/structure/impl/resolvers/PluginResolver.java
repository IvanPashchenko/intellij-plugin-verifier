package com.intellij.structure.impl.resolvers;

import com.google.common.base.Predicates;
import com.intellij.structure.domain.Plugin;
import com.intellij.structure.errors.IncorrectPluginException;
import com.intellij.structure.impl.domain.PluginManagerImpl;
import com.intellij.structure.impl.utils.JarsUtils;
import com.intellij.structure.impl.utils.PluginExtractor;
import com.intellij.structure.impl.utils.StringUtil;
import com.intellij.structure.resolvers.Resolver;
import org.apache.commons.io.FileUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.tree.ClassNode;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static com.intellij.structure.impl.domain.PluginManagerImpl.getFileEscapedUri;
import static com.intellij.structure.impl.domain.PluginManagerImpl.isJarOrZip;

/**
 * @author Sergey Patrikeev
 */
public class PluginResolver extends Resolver {

  private static final Pattern LIB_JAR_REGEX = Pattern.compile("([^/]+/)?lib/([^/]+\\.(jar|zip))");
  private static final Pattern CLASSES_DIR_REGEX = Pattern.compile("([^/]+/)?classes/");
  @NotNull private final File myPluginFile;
  @NotNull private final Plugin myPlugin;
  private final boolean myDeleteOnClose;
  private final Resolver myResolver;

  private PluginResolver(@NotNull Plugin plugin, @NotNull File extracted, boolean deleteOnClose) throws IncorrectPluginException {
    myPluginFile = extracted;
    myDeleteOnClose = deleteOnClose;
    myPlugin = plugin;
    myResolver = loadClasses(myPluginFile);
  }

  @NotNull
  public static PluginResolver createPluginResolver(@NotNull Plugin plugin) throws IncorrectPluginException, IOException {
    File file = plugin.getPluginFile();
    if (file.isDirectory() || (file.exists() && PluginManagerImpl.isJarOrZip(file))) {
      if (file.exists() && StringUtil.endsWithIgnoreCase(file.getName(), ".zip")) {
        File extracted = PluginExtractor.extractPlugin(file);
        return new PluginResolver(plugin, extracted, true);
      }
      return new PluginResolver(plugin, file, false);
    }
    if (!file.exists()) {
      throw new IllegalArgumentException("Plugin file doesn't exist " + file);
    }
    throw new IllegalArgumentException("Incorrect plugin file type " + file.getName() + ": expected a directory, a .zip or a .jar archive");
  }

  @NotNull
  private static String getPluginDescriptor(Plugin plugin) {
    return plugin.getPluginId() + ":" + plugin.getPluginVersion();
  }

  @Override
  public void close() {
    myResolver.close();
    if (myDeleteOnClose) {
      FileUtils.deleteQuietly(myPluginFile);
    }
  }

  @Nullable
  @Override
  public ClassNode findClass(@NotNull String className) throws IOException {
    return myResolver.findClass(className);
  }

  @Nullable
  @Override
  public Resolver getClassLocation(@NotNull String className) {
    return myResolver.getClassLocation(className);
  }

  @NotNull
  @Override
  public Set<String> getAllClasses() {
    return myResolver.getAllClasses();
  }

  @Override
  public boolean isEmpty() {
    return myResolver.isEmpty();
  }

  @Override
  public boolean containsClass(@NotNull String className) {
    return myResolver.containsClass(className);
  }

  @NotNull
  private Resolver loadClasses(@NotNull File file) {
    if (file.isDirectory()) {
      return loadClassesFromDir(file);
    } else if (file.exists() && isJarOrZip(file)) {
      return loadClassesFromZip(file);
    }
    throw new IncorrectPluginException("Plugin is not a correct file type. It must be a directory, a zip or a jar file");
  }

  private Resolver loadClassesFromZip(@NotNull File file) {
    String zipUrl = getFileEscapedUri(file);

    List<Resolver> resolvers = new ArrayList<Resolver>();

    Resolver classesDirResolver = null;

    ZipFile zipFile = null;
    try {
      zipFile = new ZipFile(file);
      Enumeration<? extends ZipEntry> entries = zipFile.entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();

        //check if classes directory
        Matcher classesDirMatcher = CLASSES_DIR_REGEX.matcher(entry.getName());
        if (classesDirMatcher.matches()) {
          String rootDir = entry.getName();
          try {
            classesDirResolver = new ZipResolver("Plugin classes directory", zipUrl, rootDir);
          } catch (IOException e) {
            throw new IncorrectPluginException("Unable to read plugin classes from " + rootDir, e);
          }
          if (!classesDirResolver.isEmpty()) {
            resolvers.add(classesDirResolver);
          }
        }

        //check if jar in lib/ directory
        Matcher libDirMatcher = LIB_JAR_REGEX.matcher(entry.getName());
        if (libDirMatcher.matches()) {
          String innerName = libDirMatcher.group(2);
          if (innerName != null) {
            String innerJarUrl = "jar:" + zipUrl + "!/" + StringUtil.trimStart(entry.getName(), "/");
            ZipResolver innerResolver;
            try {
              innerResolver = new ZipResolver(innerName, innerJarUrl, ".");
            } catch (IOException e) {
              throw new IncorrectPluginException("Unable to read plugin classes from " + entry.getName(), e);
            }
            if (!innerResolver.isEmpty()) {
              resolvers.add(innerResolver);
            }
          }
        }
      }
    } catch (IOException e) {
      throw new IncorrectPluginException("Unable to read plugin classes from " + file.getName(), e);
    } finally {
      if (zipFile != null) {
        try {
          zipFile.close();
        } catch (IOException ignored) {
        }
      }
    }

    //check if this zip archive is actually a .jar archive (someone has changed its extension from .jar to .zip)
    if (classesDirResolver == null) {
      try {
        ZipResolver rootResolver = new ZipResolver(file.getName(), zipUrl, ".");
        if (!rootResolver.isEmpty()) {
          resolvers.add(rootResolver);
        }
      } catch (IOException e) {
        throw new IncorrectPluginException("Unable to read plugin classes from " + file.getName(), e);
      }
    }


    return Resolver.createUnionResolver("Plugin resolver of " + getPluginDescriptor(myPlugin), resolvers);
  }

  private Resolver loadClassesFromDir(@NotNull File dir) throws IncorrectPluginException {
    File classesDir = new File(dir, "classes");

    List<Resolver> resolvers = new ArrayList<Resolver>();

    Collection<File> classFiles;
    boolean classesDirExists = classesDir.isDirectory();
    if (classesDirExists) {
      classFiles = FileUtils.listFiles(classesDir, new String[]{"class"}, true);
    } else {
      //it is possible that a plugin .zip-file is not actually a .zip archive, but a .jar archive (someone has renamed it)
      //so plugin classes will not be in the `classes` dir, but in the root dir itself
      classFiles = FileUtils.listFiles(dir, new String[]{"class"}, true);
    }

    Resolver rootResolver;
    try {
      rootResolver = new FilesResolver("Plugin " + (classesDirExists ? "`classes`" : "root") + " directory of " + getPluginDescriptor(myPlugin), classFiles);
    } catch (IOException e) {
      throw new IncorrectPluginException("Unable to read " + (classesDirExists ? "`classes`" : "root") + " plugin classes", e);
    }
    if (!rootResolver.isEmpty()) {
      resolvers.add(rootResolver);
    }


    try {
      File lib = new File(dir, "lib");
      if (lib.isDirectory()) {
        Collection<File> jars = JarsUtils.collectJars(lib, Predicates.<File>alwaysTrue(), true);
        Resolver libResolver = JarsUtils.makeResolver("Plugin `lib` jars: " + lib.getCanonicalPath(), jars);
        resolvers.add(libResolver);
      }
    } catch (IOException e) {
      throw new IncorrectPluginException("Unable to read `lib` directory", e);
    }

    return Resolver.createUnionResolver("Plugin resolver " + myPlugin.getPluginId(), resolvers);
  }
}
