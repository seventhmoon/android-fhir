/*
 * Copyright 2020 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.fhir.datacapture.utilities

import android.content.Context
import android.os.Environment
import android.util.Log
import com.google.android.fhir.datacapture.mapping.NpmPackageInitializationError
import com.google.android.fhir.datacapture.mapping.ResourceMapper
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import org.apache.commons.compress.utils.IOUtils
import org.hl7.fhir.r4.context.SimpleWorkerContext
import org.hl7.fhir.utilities.npm.NpmPackage

object NpmPackageProvider {

  /**
   *
   * NpmPackage containing all the [org.hl7.fhir.r4.model.StructureDefinition]s takes around 20
   * seconds to load. Therefore, reloading for each extraction is not desirable. This should happen
   * once and cache the variable throughout the app's lifecycle.
   *
   * Call [loadNpmPackage] to load it. The method handles skips the operation if it's already
   * loaded.
   */
  lateinit var npmPackage: NpmPackage
  lateinit var contextR4: SimpleWorkerContext

  fun loadSimpleWorkerContextWithPackage(context: Context): SimpleWorkerContext {
    return loadSimpleWorkerContext(loadNpmPackage(context))
  }

  fun loadSimpleWorkerContext(npmPackage: NpmPackage): SimpleWorkerContext {
    if (!this::contextR4.isInitialized) {
      contextR4 =
        SimpleWorkerContext.fromPackage(npmPackage).apply { isCanRunWithoutTerminology = true }
    }

    return contextR4
  }

  /**
   * Decompresses the hl7.fhir.r4.core archived package into app storage and loads it into memory.
   * It loads the package into [npmPackage]. The method skips any unnecessary operations. This
   * method can be called during initial app installation and run in the background so as to reduce
   * the time it takes for the whole process.
   *
   * The whole process can take 1-3 minutes on a clean installation.
   */
  fun loadNpmPackage(context: Context): NpmPackage {
    setupNpmPackage(context)

    if (!this::npmPackage.isInitialized) {
      npmPackage = NpmPackage.fromFolder(getLocalFhirCorePackageDirectory(context))
    }

    return npmPackage
  }

  private fun setupNpmPackage(context: Context) {
    val filename = "packages.fhir.org-hl7.fhir.r4.core-4.0.1.tgz"
    val outDir = getLocalFhirCorePackageDirectory(context)

    if (File(outDir + "/package/package.json").exists()) {
      return
    }
    // Create any missing folders
    File(outDir).mkdirs()

    // Copy the tgz package to private app storage
    try {
      val inputStream = context.assets.open(filename)
      val outputStream = FileOutputStream(File(getLocalNpmPackagesDirectory(context) + filename))

      IOUtils.copy(inputStream, outputStream)
      IOUtils.closeQuietly(inputStream)
      IOUtils.closeQuietly(outputStream)
    } catch (e: IOException) {
      // Delete the folders
      val packageDirectory = File(outDir)
      if (packageDirectory.exists()) {
        packageDirectory.delete()
      }

      Log.e(ResourceMapper::class.java.name, e.stackTraceToString())
      throw NpmPackageInitializationError(
        "Could not copy archived package [$filename] to app private storage",
        e
      )
    }

    // decompress the .tgz package
    TarGzipUtility.decompress(getLocalNpmPackagesDirectory(context) + filename, File(outDir))
  }

  /** Generate the path to the local npm packages directory */
  private fun getLocalNpmPackagesDirectory(context: Context): String {
    val outDir =
      Environment.getDataDirectory().getAbsolutePath() +
        "/data/${context.applicationContext.packageName}/npm_packages/"
    return outDir
  }

  /** Generate the path to the local hl7.fhir.r4.core package */
  private fun getLocalFhirCorePackageDirectory(context: Context): String {
    return getLocalNpmPackagesDirectory(context) + "hl7.fhir.r4.core#4.0.1"
  }
}