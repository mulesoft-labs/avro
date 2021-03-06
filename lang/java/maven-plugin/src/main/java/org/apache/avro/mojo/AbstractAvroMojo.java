/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.avro.mojo;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.avro.compiler.specific.SpecificCompiler;
import org.apache.avro.compiler.specific.SpecificCompiler.DateTimeLogicalTypeImplementation;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.model.fileset.FileSet;
import org.apache.maven.shared.model.fileset.util.FileSetManager;

/**
 * Base for Avro Compiler Mojos.
 */
public abstract class AbstractAvroMojo extends AbstractMojo {
  /**
   * The source directory of avro files. This directory is added to the classpath
   * at schema compiling time. All files can therefore be referenced as classpath
   * resources following the directory structure under the source directory.
   *
   * @parameter property="sourceDirectory"
   *            default-value="${basedir}/src/main/avro"
   */
  private File sourceDirectory;

  /**
   * @parameter property="outputDirectory"
   *            default-value="${project.build.directory}/generated-sources/avro"
   */
  private File outputDirectory;

  /**
   * @parameter property="sourceDirectory"
   *            default-value="${basedir}/src/test/avro"
   */
  private File testSourceDirectory;

  /**
   * @parameter property="outputDirectory"
   *            default-value="${project.build.directory}/generated-test-sources/avro"
   */
  private File testOutputDirectory;

  /**
   * The field visibility indicator for the fields of the generated class, as
   * string values of SpecificCompiler.FieldVisibility. The text is case
   * insensitive.
   *
   * @parameter default-value="PUBLIC_DEPRECATED"
   */
  private String fieldVisibility;

  /**
   * A list of files or directories that should be compiled first thus making them
   * importable by subsequently compiled schemas. Note that imported files should
   * not reference each other.
   *
   * @parameter
   */
  protected String[] imports;

  /**
   * A set of Ant-like exclusion patterns used to prevent certain files from being
   * processed. By default, this set is empty such that no files are excluded.
   *
   * @parameter
   */
  protected String[] excludes = new String[0];

  /**
   * A set of Ant-like exclusion patterns used to prevent certain files from being
   * processed. By default, this set is empty such that no files are excluded.
   *
   * @parameter
   */
  protected String[] testExcludes = new String[0];

  /**
   * The Java type to use for Avro strings. May be one of CharSequence, String or
   * Utf8. CharSequence by default.
   *
   * @parameter property="stringType"
   */
  protected String stringType = "CharSequence";

  /**
   * The directory (within the java classpath) that contains the velocity
   * templates to use for code generation. The default value points to the
   * templates included with the avro-maven-plugin.
   *
   * @parameter property="templateDirectory"
   */
  protected String templateDirectory = "/org/apache/avro/compiler/specific/templates/java/classic/";

  /**
   * The qualified names of classes which the plugin will look up, instantiate
   * (through an empty constructor that must exist) and set up to be injected into
   * Velocity templates by Avro compiler.
   *
   * @parameter property="velocityToolsClassesNames"
   */
  protected String[] velocityToolsClassesNames = new String[0];

  /**
   * The createOptionalGetters parameter enables generating the getOptional...
   * methods that return an Optional of the requested type. This works ONLY on
   * Java 8+
   *
   * @parameter property="createOptionalGetters"
   */
  protected boolean createOptionalGetters = false;

  /**
   * The gettersReturnOptional parameter enables generating get... methods that
   * return an Optional of the requested type. This will replace the This works
   * ONLY on Java 8+
   *
   * @parameter property="gettersReturnOptional"
   */
  protected boolean gettersReturnOptional = false;

  /**
   * Determines whether or not to create setters for the fields of the record. The
   * default is to create setters.
   *
   * @parameter default-value="true"
   */
  protected boolean createSetters;

  /**
   * A set of fully qualified class names of custom
   * {@link org.apache.avro.Conversion} implementations to add to the compiler.
   * The classes must be on the classpath at compile time and whenever the Java
   * objects are serialized.
   *
   * @parameter property="customConversions"
   */
  protected String[] customConversions = new String[0];

  /**
   * Determines whether or not to use Java classes for decimal types
   *
   * @parameter default-value="false"
   */
  protected boolean enableDecimalLogicalType;

  /**
   * Determines which type of classes to generate for date/time related logical
   * types. Either 'joda' or 'jsr310'. Defaults to jsr310.
   *
   * @parameter default-value="jsr310"
   */
  protected String dateTimeLogicalTypeImplementation = DateTimeLogicalTypeImplementation.JSR310.name().toLowerCase();

  /**
   * The current Maven project.
   *
   * @parameter default-value="${project}"
   * @readonly
   * @required
   */
  protected MavenProject project;

  @Override
  public void execute() throws MojoExecutionException {
    boolean hasSourceDir = null != sourceDirectory && sourceDirectory.isDirectory();
    boolean hasImports = null != imports;
    boolean hasTestDir = null != testSourceDirectory && testSourceDirectory.isDirectory();
    if (!hasSourceDir && !hasTestDir) {
      throw new MojoExecutionException("neither sourceDirectory: " + sourceDirectory + " or testSourceDirectory: "
          + testSourceDirectory + " are directories");
    }

    if (hasImports) {
      for (String importedFile : imports) {
        File file = new File(importedFile);
        if (file.isDirectory()) {
          String[] includedFiles = getIncludedFiles(file.getAbsolutePath(), excludes, getIncludes());
          getLog().info("Importing Directory: " + file.getAbsolutePath());
          getLog().debug("Importing Directory Files: " + Arrays.toString(includedFiles));
          compileFiles(includedFiles, file, outputDirectory);
        } else if (file.isFile()) {
          getLog().info("Importing File: " + file.getAbsolutePath());
          compileFiles(new String[] { file.getName() }, file.getParentFile(), outputDirectory);
        }
      }
    }

    if (hasSourceDir) {
      String[] includedFiles = getIncludedFiles(sourceDirectory.getAbsolutePath(), excludes, getIncludes());
      compileFiles(includedFiles, sourceDirectory, outputDirectory);
    }

    if (hasImports || hasSourceDir) {
      project.addCompileSourceRoot(outputDirectory.getAbsolutePath());
    }

    if (hasTestDir) {
      String[] includedFiles = getIncludedFiles(testSourceDirectory.getAbsolutePath(), testExcludes, getTestIncludes());
      compileFiles(includedFiles, testSourceDirectory, testOutputDirectory);
      project.addTestCompileSourceRoot(testOutputDirectory.getAbsolutePath());
    }
  }

  private String[] getIncludedFiles(String absPath, String[] excludes, String[] includes) {
    FileSetManager fileSetManager = new FileSetManager();
    FileSet fs = new FileSet();
    fs.setDirectory(absPath);
    fs.setFollowSymlinks(false);

    // exclude imports directory since it has already been compiled.
    if (imports != null) {
      String importExclude = null;

      for (String importFile : this.imports) {
        File file = new File(importFile);

        if (file.isDirectory()) {
          importExclude = file.getName() + "/**";
        } else if (file.isFile()) {
          importExclude = "**/" + file.getName();
        }

        fs.addExclude(importExclude);
      }
    }
    for (String include : includes) {
      fs.addInclude(include);
    }
    for (String exclude : excludes) {
      fs.addExclude(exclude);
    }
    return fileSetManager.getIncludedFiles(fs);
  }

  private void compileFiles(String[] files, File sourceDir, File outDir) throws MojoExecutionException {
    for (String filename : files) {
      try {
        doCompile(filename, sourceDir, outDir);
      } catch (IOException e) {
        throw new MojoExecutionException("Error compiling protocol file " + filename + " to " + outDir, e);
      }
    }
  }

  protected SpecificCompiler.FieldVisibility getFieldVisibility() {
    try {
      String upper = String.valueOf(this.fieldVisibility).trim().toUpperCase();
      return SpecificCompiler.FieldVisibility.valueOf(upper);
    } catch (IllegalArgumentException e) {
      return SpecificCompiler.FieldVisibility.PRIVATE;
    }
  }

  protected DateTimeLogicalTypeImplementation getDateTimeLogicalTypeImplementation() {
    try {
      if (this.dateTimeLogicalTypeImplementation == null || this.dateTimeLogicalTypeImplementation.isEmpty()) {
        return DateTimeLogicalTypeImplementation.DEFAULT;
      } else {
        String upper = String.valueOf(this.dateTimeLogicalTypeImplementation).trim().toUpperCase();
        return DateTimeLogicalTypeImplementation.valueOf(upper);
      }
    } catch (IllegalArgumentException e) {
      getLog().warn("Unknown value '" + this.dateTimeLogicalTypeImplementation
          + "' for property dateTimeLogicalTypeImplementation; using '"
          + DateTimeLogicalTypeImplementation.DEFAULT.name().toLowerCase() + "' instead");
      return DateTimeLogicalTypeImplementation.DEFAULT;
    }
  }

  protected List<Object> instantiateAdditionalVelocityTools() {
    List<Object> velocityTools = new ArrayList<>(velocityToolsClassesNames.length);
    for (String velocityToolClassName : velocityToolsClassesNames) {
      try {
        Class klass = Class.forName(velocityToolClassName);
        velocityTools.add(klass.newInstance());
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
    return velocityTools;
  }

  protected abstract void doCompile(String filename, File sourceDirectory, File outputDirectory) throws IOException;

  protected URLClassLoader createClassLoader() throws DependencyResolutionRequiredException, MalformedURLException {
    List<URL> urls = appendElements(project.getRuntimeClasspathElements());
    urls.addAll(appendElements(project.getTestClasspathElements()));
    return new URLClassLoader(urls.toArray(new URL[0]), Thread.currentThread().getContextClassLoader());
  }

  private List<URL> appendElements(List runtimeClasspathElements) throws MalformedURLException {
    List<URL> runtimeUrls = new ArrayList<>();
    if (runtimeClasspathElements != null) {
      for (Object runtimeClasspathElement : runtimeClasspathElements) {
        String element = (String) runtimeClasspathElement;
        runtimeUrls.add(new File(element).toURI().toURL());
      }
    }
    return runtimeUrls;
  }

  protected abstract String[] getIncludes();

  protected abstract String[] getTestIncludes();

}
