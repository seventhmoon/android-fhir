/*
 * Copyright 2020 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.mlkit.md

import android.animation.AnimatorInflater
import android.animation.AnimatorSet
import android.hardware.Camera
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.View.OnClickListener
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProviders
import com.google.android.fhir.datacapture.R
import com.google.android.fhir.datacapture.views.DatePickerFragment
import com.google.android.material.chip.Chip
import com.google.common.base.Objects
import com.google.mlkit.md.barcodedetection.BarcodeField
import com.google.mlkit.md.barcodedetection.BarcodeProcessor
import com.google.mlkit.md.barcodedetection.BarcodeResultFragment
import com.google.mlkit.md.camera.CameraSource
import com.google.mlkit.md.camera.CameraSourcePreview
import com.google.mlkit.md.camera.GraphicOverlay
import com.google.mlkit.md.camera.WorkflowModel
import com.google.mlkit.md.camera.WorkflowModel.WorkflowState
import java.io.IOException
import java.util.ArrayList

/** Demonstrates the barcode scanning workflow using camera preview. */
class LiveBarcodeScanningFragment : DialogFragment(), OnClickListener {

  private var cameraSource: CameraSource? = null
  private var preview: CameraSourcePreview? = null
  private var graphicOverlay: GraphicOverlay? = null
  private var flashButton: View? = null
  private var promptChip: Chip? = null
  private var promptChipAnimator: AnimatorSet? = null
  private var workflowModel: WorkflowModel? = null
  private var currentWorkflowState: WorkflowState? = null

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?
  ): View? {
    super.onCreate(savedInstanceState)
    val rootView = inflater.inflate(R.layout.fragment_live_barcode, container, false)

    preview = rootView.findViewById(R.id.camera_preview)
    graphicOverlay =
      rootView.findViewById<GraphicOverlay>(R.id.camera_preview_graphic_overlay).apply {
        setOnClickListener(this@LiveBarcodeScanningFragment)
        cameraSource = CameraSource(this)
      }

    promptChip = rootView.findViewById(R.id.bottom_prompt_chip)
    promptChipAnimator =
      (AnimatorInflater.loadAnimator(context, R.animator.bottom_prompt_chip_enter) as AnimatorSet)
        .apply { setTarget(promptChip) }

    rootView.findViewById<View>(R.id.close_button).setOnClickListener(this)
    flashButton =
      rootView.findViewById<View>(R.id.flash_button).apply {
        setOnClickListener(this@LiveBarcodeScanningFragment)
      }

    setUpWorkflowModel()

    return rootView
  }

  override fun onResume() {
    super.onResume()

    workflowModel?.markCameraFrozen()
    currentWorkflowState = WorkflowState.NOT_STARTED
    cameraSource?.setFrameProcessor(BarcodeProcessor(graphicOverlay!!, workflowModel!!))
    workflowModel?.setWorkflowState(WorkflowState.DETECTING)
  }

  override fun onPause() {
    super.onPause()
    currentWorkflowState = WorkflowState.NOT_STARTED
    stopCameraPreview()
  }

  override fun onDestroy() {
    super.onDestroy()
    cameraSource?.release()
    cameraSource = null
  }

  override fun onClick(view: View) {
    when (view.id) {
      R.id.close_button ->
        requireActivity().supportFragmentManager.beginTransaction().remove(this).commit()
      R.id.flash_button -> {
        flashButton?.let {
          if (it.isSelected) {
            it.isSelected = false
            cameraSource?.updateFlashMode(Camera.Parameters.FLASH_MODE_OFF)
          } else {
            it.isSelected = true
            cameraSource!!.updateFlashMode(Camera.Parameters.FLASH_MODE_TORCH)
          }
        }
      }
    }
  }

  private fun startCameraPreview() {
    val workflowModel = this.workflowModel ?: return
    val cameraSource = this.cameraSource ?: return
    if (!workflowModel.isCameraLive) {
      try {
        workflowModel.markCameraLive()
        preview?.start(cameraSource)
      } catch (e: IOException) {
        Log.e(TAG, "Failed to start camera preview!", e)
        cameraSource.release()
        this.cameraSource = null
      }
    }
  }

  private fun stopCameraPreview() {
    val workflowModel = this.workflowModel ?: return
    if (workflowModel.isCameraLive) {
      workflowModel.markCameraFrozen()
      flashButton?.isSelected = false
      preview?.stop()
    }
  }

  private fun setUpWorkflowModel() {
    workflowModel = ViewModelProviders.of(this).get(WorkflowModel::class.java)

    // Observes the workflow state changes, if happens, update the overlay view indicators and
    // camera preview state.
    workflowModel!!.workflowState.observe(
      viewLifecycleOwner,
      Observer { workflowState ->
        if (workflowState == null || Objects.equal(currentWorkflowState, workflowState)) {
          return@Observer
        }

        currentWorkflowState = workflowState
        Log.d(TAG, "Current workflow state: ${currentWorkflowState!!.name}")

        val wasPromptChipGone = promptChip?.visibility == View.GONE

        when (workflowState) {
          WorkflowState.DETECTING -> {
            promptChip?.visibility = View.VISIBLE
            promptChip?.setText(R.string.prompt_point_at_a_barcode)
            startCameraPreview()
          }
          WorkflowState.CONFIRMING -> {
            promptChip?.visibility = View.VISIBLE
            promptChip?.setText(R.string.prompt_move_camera_closer)
            startCameraPreview()
          }
          WorkflowState.SEARCHING -> {
            promptChip?.visibility = View.VISIBLE
            promptChip?.setText(R.string.prompt_searching)
            stopCameraPreview()
          }
          WorkflowState.DETECTED, WorkflowState.SEARCHED -> {
            promptChip?.visibility = View.GONE
            stopCameraPreview()
          }
          else -> promptChip?.visibility = View.GONE
        }

        val shouldPlayPromptChipEnteringAnimation =
          wasPromptChipGone && promptChip?.visibility == View.VISIBLE
        promptChipAnimator?.let {
          if (shouldPlayPromptChipEnteringAnimation && !it.isRunning) it.start()
        }
      }
    )

    workflowModel?.detectedBarcode?.observe(
      viewLifecycleOwner,
      Observer { barcode ->
        if (barcode != null) {
//          val barcodeFieldList = ArrayList<BarcodeField>()
//          barcodeFieldList.add(BarcodeField("Raw Value", barcode.rawValue ?: ""))
//          BarcodeResultFragment.show(requireActivity().supportFragmentManager, barcodeFieldList)

          setFragmentResult(
            "result",
            bundleOf(
              "result" to barcode.rawValue,
            )
          )
          dismiss()
        }
      }
    )
  }

  companion object {
    private const val TAG = "LiveBarcodeActivity"
  }
}