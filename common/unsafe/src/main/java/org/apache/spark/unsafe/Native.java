/*
 * Copyright (c) 2017 SnappyData, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */
package org.apache.spark.unsafe;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLDecoder;
import java.security.CodeSource;
import java.util.Locale;

import org.apache.log4j.Logger;

/**
 * Optimized JNI calls.
 */
public final class Native {

  public static final int MIN_JNI_SIZE = Integer.getInteger("spark.utf8.jniSize", 32);

  public static final boolean debug;
  private static final Logger logger;

  private static boolean isMac;
  private static boolean isWindows;
  private static boolean isSolaris;

  private static final boolean is64Bit;
  private static final boolean nativeLoaded;

  private Native() {
  }

  static {
    debug = Boolean.getBoolean("spark.native.debug");

    String suffix = "";
    String os = System.getProperty("os.name").toLowerCase(Locale.ENGLISH);
    if (os.startsWith("mac") || os.startsWith("darwin")) {
      isMac = true;
      // no suffix since library extension will be different
    } else if (os.startsWith("windows")) {
      isWindows = true;
      // no suffix since library extension will be different
    } else if (os.startsWith("sunos") || os.startsWith("solaris")) {
      isSolaris = true;
      suffix = "_sol";
    }

    String arch = System.getProperty("os.arch");
    is64Bit = arch.contains("64") || arch.contains("s390x");

    logger = Logger.getLogger(Native.class);

    String library = "native" + suffix;
    if (is64Bit()) {
      library += "64";
    }
    if (debug) {
      library += "_g";
    }

    boolean loaded = false;
    CodeSource cs = Native.class.getProtectionDomain().getCodeSource();
    URL jarURL = cs != null ? cs.getLocation() : null;
    String libDir;
    try {
      if (jarURL != null) {
        libDir = new File(URLDecoder.decode(jarURL.getFile(), "UTF-8"))
            .getParentFile().getCanonicalPath();
      } else {
        // try in SNAPPY_HOME and SPARK_HOME
        String productHome = System.getenv("SNAPPY_HOME");
        if (productHome == null) {
          productHome = System.getenv("SPARK_HOME");
        }
        if (productHome == null) {
          throw new IllegalStateException("Unable to locate jar location");
        }
        libDir = new File(productHome, "jars").getCanonicalPath();
      }
      File libraryPath = new File(libDir, System.mapLibraryName(library));
      if (libraryPath.exists()) {
        System.load(libraryPath.getPath());
        logger.info("library " + library + " loaded from " + libraryPath);
      } else {
        System.loadLibrary(library);
        logger.info("library " + library + " loaded from system path");
      }

      loaded = true;
    } catch (IOException ioe) {
      if (logger.isInfoEnabled()) {
        logger.info("library " + library + " could not be loaded due to " + ioe);
      }
    } catch (UnsatisfiedLinkError ule) {
      if (logger.isInfoEnabled()) {
        logger.info("library " + library + " could not be loaded");
      }
    }
    nativeLoaded = loaded;
  }

  public static boolean is64Bit() {
    return is64Bit;
  }

  public static boolean isMac() {
    return isMac;
  }

  public static boolean isWindows() {
    return isWindows;
  }

  public static boolean isSolaris() {
    return isSolaris;
  }

  public static boolean isLoaded() {
    return nativeLoaded;
  }

  public static native boolean arrayEquals(long leftAddress,
      long rightAddress, long size);

  public static native int compareString(long leftAddress,
      long rightAddress, long size);

  public static native boolean containsString(long sourceAddress,
      int sourceSize, long destAddress, int destSize);
}
