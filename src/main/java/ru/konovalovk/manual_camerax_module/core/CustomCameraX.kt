package ru.konovalovk.manual_camerax_module.core

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.RggbChannelVector
import android.net.Uri
import android.os.Build
import android.os.CountDownTimer
import android.os.Environment
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.MutableLiveData
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.*
import java.io.File
import java.lang.Runnable
import java.nio.file.Path
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class CustomCameraX {
    class Parameters {
        data class ManualParameters(val shutterIndex: Int, val iso: Int, val wb: Int, val focus: Int){
            fun getDifferenceWith(second: ManualParameters): DifferenceParameter {
                val differentParameter = mutableListOf<DifferenceParameter>()

                if (shutterIndex != second.shutterIndex) differentParameter.add(DifferenceParameter(shutterIndex, second.shutterIndex, ManualParametersType.SHUTTER))
                if (iso != second.iso) differentParameter.add(DifferenceParameter(iso, second.iso,  ManualParametersType.ISO))
                if (wb != second.wb) differentParameter.add(DifferenceParameter(wb, second.wb, ManualParametersType.WB))
                if (focus != second.focus) { differentParameter.add(DifferenceParameter(focus, second.focus, ManualParametersType.FOCUS)) }

                return when(differentParameter.size) {
                    1 -> differentParameter[0]
                    else -> DifferenceParameter(0,0, ManualParametersType.NONE)
                }
            }
        }

        data class DifferenceParameter(val firstPhoto: Int, val lastPhoto: Int, val type: ManualParametersType){
            fun intermediateValues(numPhotos: Int): List<Int>{
                val firstValue = if(firstPhoto < lastPhoto) firstPhoto else lastPhoto
                val lastValue = if(firstPhoto < lastPhoto) lastPhoto else firstPhoto

                val arrayOfValues = mutableListOf<Int>()
                var currentValue = firstValue
                val calculatedStep = lastValue / numPhotos
                val step = if(calculatedStep == 0) 1 else calculatedStep

                for (x in firstValue..lastValue){
                    if(currentValue <= lastValue){
                        arrayOfValues.add(currentValue)
                        currentValue += step
                    }
                }

                return arrayOfValues
            }
        }

        val empty = ManualParameters(0,0,0,0)

    }
    private val TAG = "CustomCameraX"
    //Internal Camera
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private val analysisExecutor: Executor = Executors.newSingleThreadExecutor()

    val errorMessage = MutableLiveData<String>("")

    //Internal Camera Settings
    private var displayId: Int = -1
    private var lensFacing: Int = CameraSelector.LENS_FACING_BACK
    private val RATIO_4_3_VALUE = 4.0 / 3.0
    private val RATIO_16_9_VALUE = 16.0 / 9.0

    //EV & WB
    val wb = MutableLiveData<Int>()
    val focus = MutableLiveData<Int>()
    val maxFocus = MutableLiveData<Int>()
    val iso = MutableLiveData<Int>()
    val maxIso = MutableLiveData<Int>()
    val frameDuration = MutableLiveData<Int>()
    val maxFrameDuration = MutableLiveData<Long>()
    private fun MutableLiveData<Int>.notNullValue() = value ?: 0
    val shutter = MutableLiveData<Int>(0)
    var shutterSpeeds = listOf(
        30.0,
        15.0,
        8.0,
        4.0,
        2.0,
        1.0,
        1.0 / 2,
        1.0 / 4,
        1.0 / 8,
        1.0 / 15,
        1.0 / 30,
        1.0 / 60,
        1.0 / 125,
        1.0 / 250,
        1.0 / 500,
        1.0 / 1000,
        1.0 / 2000,
        1.0 / 4000,
        1.0 / 8000
    )
        .filter { it < 1.0 } // less than second
        .reversed()
    val maxShutter = MutableLiveData<Int>()
    //Auto switch
    val autoWB = MutableLiveData<Boolean>()
    val autoExposition = MutableLiveData<Boolean>()
    val autoFocus = MutableLiveData<Boolean>()
    val flash = MutableLiveData<Boolean>()
    val capturedPhotoCount = MutableLiveData<Int>()
    private fun MutableLiveData<Boolean>.notNullValue() = value ?: false

    private val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"

    //timer
    private var photoTimer: CountDownTimer? = null
    private var isPhotoTimerWork = false
    private val SECOND = 1000L

    //interval&batch
    var intervalBetweenShot = 0
    var numPhotos = 0

    //
    var firstPhotoSettings = Parameters().empty
    var lastPhotoSettings = Parameters().empty
    var differenceParameter = Parameters.DifferenceParameter(0, 0, ManualParametersType.NONE)
    val isBracketingReady = MutableLiveData<Boolean>(false)
    var isCameraRebinded = false
    var isPhotoCaptured = false

    val lastCapturedUri = MutableLiveData<Pair<String, String>>()

    fun initCamera(viewLifecycleOwner: LifecycleOwner, internalCameraView: PreviewView) {
        // Get screen metrics used to setup camera for full screen resolution
        val metrics = DisplayMetrics().also { internalCameraView.display.getRealMetrics(it) }
        val screenAspectRatio = aspectRatio(metrics.widthPixels, metrics.heightPixels)
        val rotation = internalCameraView.display.rotation

        Log.d(TAG, "Screen metrics: ${metrics.widthPixels} x ${metrics.heightPixels}")
        Log.d(TAG, "Preview aspect ratio: $screenAspectRatio")

        val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        // Bind the CameraProvider to the LifeCycleOwner
        val cameraProviderFuture = ProcessCameraProvider.getInstance(internalCameraView.context)
        cameraProviderFuture.addListener(
            futureListener(
                cameraProviderFuture,
                screenAspectRatio,
                rotation,
                cameraSelector,
                internalCameraView,
                viewLifecycleOwner
            ), ContextCompat.getMainExecutor(internalCameraView.context)
        )
    }

    fun logAndSetupAvailableCameraSettings(context: Context){
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cameraManager.cameraIdList.forEach { id ->
            var cameraLog = "Camera $id:"
            val cameraCharacteristics = cameraManager.getCameraCharacteristics(id)
            val facing = cameraCharacteristics.get(CameraCharacteristics.LENS_FACING)

            //Supported HW Level
            cameraCharacteristics
                .get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                ?.apply {
                    val isCameraSupportFullCapabilities =
                        (this == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL)
                    if (isCameraSupportFullCapabilities) cameraLog += "\nHardwareLevel Full: $isCameraSupportFullCapabilities"
                    else if((facing == CameraSelector.LENS_FACING_BACK)) errorMessage.postValue("Warning: Back camera on device doesn't support full capabilities")
                }
            //AE
            cameraCharacteristics
                .get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?.apply { if (isEmpty()) return }
                ?.forEach { capability ->
                    if (capability == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) cameraLog += "\nManual AE: available"
                }
            //FOCUS
            cameraCharacteristics
                .get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)?.also {
                    cameraLog += "\nMax focus: $it"
                    if(id == "0") maxFocus.postValue(it.toInt())
                }
            //ISO
            cameraCharacteristics
                .get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                ?.run {
                    cameraLog += "\nISO: $lower - $upper"
                    if(id == "0") {
                        iso.postValue(lower)
                        maxIso.postValue(upper)
                    }
                }
            //Exposure
            cameraCharacteristics
                .get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                ?.run {
                    cameraLog += "\nExposure time: ${lower.toMS()}ms - ${upper.toMS()}ms"
                    if(id == "0") {
                        shutterSpeeds = shutterSpeeds.filter {
                            val currentShutterInNS = it.toNanoSecond() //some magic formula
                            val isSupportedByCamera = currentShutterInNS in lower..upper
                            isSupportedByCamera
                        }
                        maxShutter.postValue(shutterSpeeds.lastIndex)
                    }
                }
            //FrameDuration
            cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION)
                ?.run {
                    cameraLog += "\nMax Frame Duration time: ${this.toMS()}ms"
                    if(id == "0") {
                        frameDuration.postValue(this.toMS())
                        maxFrameDuration.postValue(this)
                    }
                }

            Log.i(TAG, cameraLog)
            //if (facing == lensFacing) return@forEach
        }
    }

    private fun futureListener(
        cameraProviderFuture: ListenableFuture<ProcessCameraProvider>,
        screenAspectRatio: Int,
        rotation: Int,
        cameraSelector: CameraSelector,
        internalCameraView: PreviewView,
        viewLifecycleOwner: LifecycleOwner
    ) = Runnable {
        val custoMSize = Size(1920, 1080)

        cameraProvider = cameraProviderFuture.get()
        cameraProvider?.unbindAll() // Must unbind the use-cases before rebinding them.

        logCurrentCameraSettings()
        preview = setupAndBuildPreview(custoMSize, rotation, screenAspectRatio)
        imageCapture = setupAndBuildImageCapture(screenAspectRatio, rotation)

        bindCamera(viewLifecycleOwner, internalCameraView, cameraSelector)
    }

    private fun logCurrentCameraSettings(){
        var log = "Current Camera Settings:"
        log += "\nFocus: ${focus.notNullValue()}"
        log += "\nISO: ${iso.notNullValue()}"
        log += "\nShutter: ${shutter.notNullValue()}ms"
        //log += "\nFrame Duration: ${frameDuration.value}ms"
        Log.i(TAG, log)
    }

    private fun setupAndBuildPreview(custoMSize: Size, rotation: Int, screenAspectRatio: Int): Preview = Preview.Builder().let {
        it.setTargetAspectRatio(screenAspectRatio)
        //it.setTargetResolution(custoMSize)
        it.setTargetRotation(rotation)
        attachSettingsTo(it)

        it.build()
    }

    private fun setupAndBuildImageCapture(screenAspectRatio: Int, rotation: Int) = ImageCapture.Builder().let {
        it.setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
        it.setTargetAspectRatio(screenAspectRatio)
        it.setTargetRotation(rotation)
        it.setFlashMode(if (flash.notNullValue()) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF)
        attachSettingsTo(it)
        it.build()
    }

    private fun attachSettingsTo(useCaseBuilder: Any){
        Camera2Interop.Extender(useCaseBuilder as ExtendableBuilder<*>).run {
            if(flash.notNullValue())setCaptureRequestOption(
                CaptureRequest.FLASH_MODE,
                CameraMetadata.FLASH_MODE_TORCH
            )
            else setCaptureRequestOption(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
            // adjust WB using seekbar's params
            if (autoWB.notNullValue()) setCaptureRequestOption(
                CaptureRequest.CONTROL_AWB_MODE,
                CameraMetadata.CONTROL_AWB_MODE_AUTO
            )
            else {
                setCaptureRequestOption(
                    CaptureRequest.CONTROL_AWB_MODE,
                    CameraMetadata.CONTROL_AWB_MODE_OFF
                )
                setCaptureRequestOption(
                    CaptureRequest.COLOR_CORRECTION_MODE,
                    CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX
                )
                setCaptureRequestOption(
                    CaptureRequest.COLOR_CORRECTION_GAINS,
                    colorTemperature(wb.notNullValue())
                )
            }
            // abjust FOCUS using seekbar's params
            if (autoFocus.notNullValue()) setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            )
            else {
                setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE,
                    CameraMetadata.CONTROL_AF_MODE_OFF
                )
                setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focus.notNullValue().toFloat())
            }
            // abjust ISO\Shutter using seekbar's params
            if (autoExposition.notNullValue()) setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_MODE,
                CameraMetadata.CONTROL_AE_MODE_ON
            )
            else {
                /** If we disabling auto-exposure, we need to set the exposure time, in addition to the sensitivity.
                You also preferably need to set the frame duration, though the defaults for both are probably 1/30s */
                setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_MODE,
                    CameraMetadata.CONTROL_AE_MODE_OFF
                )
                setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso.notNullValue())

                // abjust Exposure using seekbar's params
                val evChoice = (shutterSpeeds[shutter.notNullValue()])
                setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, evChoice.toNanoSecond()) //MS -> NS (1.0/60) * 1000).toNanoSecond() also preview FPS
            }
        }
    }

    @SuppressLint("RestrictedApi")
    private fun bindCamera(
        viewLifecycleOwner: LifecycleOwner,
        internalCameraView: PreviewView,
        cameraSelector: CameraSelector
    ) {
        try {
            // A variable number of use-cases can be passed here - camera provides access to CameraControl & CameraInfo
            camera = cameraProvider?.bindToLifecycle(viewLifecycleOwner, cameraSelector, preview, imageCapture)

            val captureSize = imageCapture?.attachedSurfaceResolution ?: Size(0, 0)
            val previewSize = preview?.attachedSurfaceResolution ?: Size(0, 0)
            val analyzeSize = imageAnalyzer?.attachedSurfaceResolution ?: Size(0, 0)

            Log.i(TAG, "Use case res: capture_$captureSize preview_$previewSize analyze_$analyzeSize")
            preview?.setSurfaceProvider(internalCameraView.createSurfaceProvider())
            isCameraRebinded = true
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun aspectRatio(width: Int, height: Int): Int {
        val previewRatio = max(width, height).toDouble() / min(width, height)
        if (abs(previewRatio - RATIO_4_3_VALUE) <= abs(previewRatio - RATIO_16_9_VALUE)) {
            return AspectRatio.RATIO_4_3
        }
        return AspectRatio.RATIO_16_9
    }

    private fun colorTemperature(wbFactor: Int): RggbChannelVector { //0..100
        return RggbChannelVector(
            0.635f + 0.0208333f * wbFactor,
            1.0f,
            1.0f,
            3.7420394f + -0.0287829f * wbFactor
        )
    }

    private fun Long.toMS(): Int = (this / million()).toInt() // NS -> MS
    private fun Int.toNanoSecond(): Long = (this * million()) // MS -> NS
    private fun Double.toNanoSecond(): Long = (this * million() * 1000).toLong()
    private fun million() = (1 * 1000 * 1000L)

    fun takePhoto(context: Context) {
        val appName = context.applicationInfo.processName
        val fileName = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())
        val outputOptions = getOutputFileOptions(context.contentResolver, appName, fileName)

        isPhotoCaptured = false

        //wait for relaunch useCase
        imageCapture?.takePicture(outputOptions, ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    outputFileResults.savedUri?.run {
                        replaceImageInPictureDir(context, this, appName)?.run {
                            lastCapturedUri.postValue(this)
                        }
                        isPhotoCaptured = true
                        capturedPhotoCount.value = capturedPhotoCount.notNullValue() + 1
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed", exception)
                }
            })
    }

    private fun getOutputFileOptions(contentResolver: ContentResolver, appName: String, fileName: String): ImageCapture.OutputFileOptions =
        ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            getContentValues(appName, fileName)
        ).build()

    private fun getContentValues(appName: String, fileName: String): ContentValues =
        ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$appName")
                put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
            }
        }

    private fun replaceImageInPictureDir(context: Context, photoFileUri: Uri, appName: String): Pair<String, String>? {
        val photoFilePath = UriFileUtils.getPath(context, photoFileUri) ?: return null
        val oldFileName = photoFilePath.split("/").last()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val oldPhotoFile = File(photoFilePath)
            val newFileName = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())
            val storageDir = "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).path}/$appName"
            val newPhotoFile = File("$storageDir/$newFileName.${oldPhotoFile.extension}")

            oldPhotoFile.run {
                copyTo(newPhotoFile, false)
                delete()
            }

            //ToDo: to LiveData
            Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE).run {
                data = Uri.fromFile(newPhotoFile)
                context.sendBroadcast(this)
            }

            return Pair("$storageDir/$newFileName.${oldPhotoFile.extension}","$newFileName.${oldPhotoFile.extension}") //path&filename
           // Toast.makeText(context, "Photo captured to\n${newPhotoFile.absolutePath}", Toast.LENGTH_SHORT).show()
        }

        return Pair(photoFilePath, oldFileName)
        //else Toast.makeText(context, "Photo captured to\nPictures/$appName", Toast.LENGTH_SHORT).show()
    }

    fun initPhotoTimer(context: Context, interval: Long, numPhotos: Long){
        if(isPhotoTimerWork) {
            isPhotoTimerWork = !isPhotoTimerWork
            photoTimer?.cancel()
        } else {
            val totalTimeForOnePhotoSeries = numPhotos * interval
            Toast.makeText(context, "Timer set with interval $interval & numPhotos: $numPhotos:", Toast.LENGTH_SHORT).show()

            photoTimer = object : CountDownTimer(totalTimeForOnePhotoSeries * SECOND, interval * SECOND){
                override fun onTick(p0: Long) {

                    takePhoto(context)
                }

                override fun onFinish() {
                    isPhotoTimerWork = false
                    Toast.makeText(context, "Capture series over", Toast.LENGTH_SHORT).show()
                }
            }
            isPhotoTimerWork = !isPhotoTimerWork
            photoTimer?.start()
        }
    }

    fun prepareForBracketing() {
        if (firstPhotoSettings == lastPhotoSettings) return
        val smth = firstPhotoSettings.getDifferenceWith(lastPhotoSettings)
        smth.run {
            when(type){
                ManualParametersType.NONE -> return
                else -> {
                    Log.i(TAG, "$type:$firstPhoto->$lastPhoto")
                    differenceParameter = this
                    isBracketingReady.postValue(true)
                }
            }
        }
    }

    fun launchBracketing(context: Context, interval: Int = 1, numPhotos: Int = 1) =
        CoroutineScope(Dispatchers.Default).launch {
            val arrayOfValues = differenceParameter.intermediateValues(numPhotos)
            arrayOfValues.forEach { currentValue ->
                provideParametersShift(currentValue)
                delay(interval * SECOND)

                takePhoto(context)
                while (isActive) { //
                    if (isPhotoCaptured) break
                    else delay(50)
                }
            }
            withContext(Dispatchers.Main) { Toast.makeText(context, "Capture series over", Toast.LENGTH_SHORT).show() }
    }

    private fun provideParametersShift(currentValue: Int) =
        when (differenceParameter.type) {
            ManualParametersType.SHUTTER -> shutter.postValue(currentValue)
            ManualParametersType.ISO -> iso.postValue(currentValue)
            ManualParametersType.WB -> wb.postValue(currentValue)
            ManualParametersType.FOCUS -> focus.postValue(currentValue)
            else -> { }
        }
}