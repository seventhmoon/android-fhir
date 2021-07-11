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

package com.google.android.fhir.sync

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.impl.utils.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.FhirEngineBuilder
import com.google.android.fhir.FhirServices
import com.google.android.fhir.db.Database
import com.google.android.fhir.impl.FhirEngineImpl
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineDispatcher
import kotlinx.coroutines.test.runBlockingTest
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.ResourceType
import org.junit.Assert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.Spy
import org.mockito.kotlin.any
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P])
class SyncJobTest {
  private lateinit var workManager: WorkManager
  private lateinit var fhirEngine: FhirEngine

  @Mock
  private lateinit var database: Database

  @Mock
  private lateinit var dataSource: DataSource

  private lateinit var syncJob: SyncJob

  private val testDispatcher = TestCoroutineDispatcher()

  private lateinit var context: Context

  @Before
  fun setup(){
    MockitoAnnotations.openMocks(this)

    fhirEngine = FhirEngineImpl(database, ApplicationProvider.getApplicationContext())

    val resourceSyncParam = mapOf(ResourceType.Patient to mapOf("address-city" to "NAIROBI"))

    syncJob = Sync.basicSyncJob(testDispatcher, fhirEngine, dataSource, resourceSyncParam)

    context = ApplicationProvider.getApplicationContext()

    val config = Configuration.Builder()
      .setMinimumLoggingLevel(Log.DEBUG)
      .setExecutor(SynchronousExecutor())
      .build()

    // Initialize WorkManager for instrumentation tests.
    WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    workManager = WorkManager.getInstance(context)
  }

  @Test
  fun test() {
    val worker = OneTimeWorkRequestBuilder<TestSyncWorker>().build()

    workManager.enqueueUniqueWork("TEST_WORK", ExistingWorkPolicy.KEEP, worker).result.get()

    val workInfo = workManager.getWorkInfosForUniqueWork("TEST_WORK").get()

    assertTrue(workInfo[0].state.isFinished)
  }

  @Test
  fun `should run synchronizer and emit states accurately in sequence`() = runBlockingTest {
    whenever(database.getAllLocalChanges()).thenReturn(listOf())
    whenever(dataSource.loadData(any())).thenReturn(Bundle())

    val res = mutableListOf<State>()

    val job = launch {
      syncJob.subscribe().collect {
        res.add(it)
      }
    }

    syncJob.run()

    // State transition for successful job as below
    // Nothing, Started, InProgress, Success
    assertTrue(res[0] is State.Nothing)
    assertTrue(res[1] is State.Started)
    assertTrue(res[2] is State.InProgress)
    assertTrue(res[3] is State.Success)
    assertEquals(LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS),
      (res[3] as State.Success).lastSyncTimestamp.truncatedTo(ChronoUnit.SECONDS))

    assertEquals(4, res.size)

    job.cancel()
  }

  @Test
  fun `should run synchronizer and emit  with error accurately in sequence`() = runBlockingTest {
    whenever(database.getAllLocalChanges()).thenReturn(listOf())
    whenever(dataSource.loadData(any())).thenThrow(IllegalStateException::class.java)

    val res = mutableListOf<State>()

    val job = launch {
      syncJob.subscribe().collect {
        res.add(it)
      }
    }

    syncJob.run()

    // State transition for failed job as below
    // Nothing, Started, InProgress, Error
    assertTrue(res[0] is State.Nothing)
    assertTrue(res[1] is State.Started)
    assertTrue(res[2] is State.InProgress)
    assertTrue(res[3] is State.Error)
    assertEquals(LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS),
      (res[3] as State.Error).lastSyncTimestamp.truncatedTo(ChronoUnit.SECONDS))

    assertTrue((res[3] as State.Error).exceptions[0].exception is java.lang.IllegalStateException)
    assertEquals(4, res.size)

    job.cancel()
  }

  @Test
  fun `should poll accurately with given delay`() = runBlockingTest {
    // the duration to run the flow
    val duration = 1000L
    val delay = 100L

    // polling with given delay
    val flow = syncJob.poll(delay, 1);

    launch {
      // consume elements generated in given duration and given delay
      val dataList = flow.take(10).toList()
      assertEquals(dataList.size, 10)
    }

    // wait until job is completed
    testDispatcher.advanceTimeBy(duration)

    syncJob.close()
  }

  @Test
  fun `should poll accurately with given delay and initial delay`() = runBlockingTest {
    // the duration required to run the test
    val duration = 1040L
    val delay = 100L
    val initialDelay = 40L

    val flow = syncJob.poll(delay, initialDelay)

    launch {
      // consume elements generated in given duration and given delay
      val dataList = flow.take(10).toList()
      assertEquals(dataList.size, 10)
    }

    // wait until job is completed
    testDispatcher.advanceTimeBy(duration)

    syncJob.close()
  }
}
