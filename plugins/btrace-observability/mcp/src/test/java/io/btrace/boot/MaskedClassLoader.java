package io.btrace.boot;

import java.io.File;

/** Test double for the bootstrap-visible masked loader. */
public class MaskedClassLoader extends ClassLoader {
  public MaskedClassLoader(File ignoredJar, String ignoredSection, ClassLoader parent) {
    super(parent);
  }
}
