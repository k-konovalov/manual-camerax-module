package ru.konovalovk.manual_camerax_module.ui

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.camera.view.PreviewView
import androidx.core.view.doOnLayout
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Observer
import com.google.android.material.switchmaterial.SwitchMaterial
import com.warkiz.widget.IndicatorSeekBar
import com.warkiz.widget.OnSeekChangeListener
import com.warkiz.widget.SeekParams
import ru.konovalovk.manual_camerax_module.R

class CameraFragment : Fragment(R.layout.camerax_module_fragment) {

    private val singleViewModel : CameraViewModel by viewModels()

    private val sbWb by lazy { requireView().findViewById<IndicatorSeekBar>(R.id.sbWb) }
    private val sbFocus by lazy { requireView().findViewById<IndicatorSeekBar>(R.id.sbFocus) }
    private val sbISO by lazy { requireView().findViewById<IndicatorSeekBar>(R.id.sbISO) }
    private val sbShutter by lazy { requireView().findViewById<IndicatorSeekBar>(R.id.sbShutter) }
    private val sbFrameDuration by lazy { requireView().findViewById<IndicatorSeekBar>(R.id.sbFrameDuration) }

    private val switchAf by lazy { requireView().findViewById<SwitchMaterial>(R.id.sAF) }
    private val switchAutoIsoShutter by lazy { requireView().findViewById<SwitchMaterial>(R.id.sAutoIsoShutter) }
    private val switchAutoWB by lazy { requireView().findViewById<SwitchMaterial>(R.id.sAutoWB) }
    private val switchFlash by lazy { requireView().findViewById<SwitchMaterial>(R.id.sFlash) }

    private val fabTakePicture by lazy { requireView().findViewById<ImageButton>(R.id.sFlash) }

    private val etDelayBetweenPhoto by lazy { requireView().findViewById<EditText>(R.id.etDelayBetweenPhoto) }
    private val etNumberOfPhotos by lazy { requireView().findViewById<EditText>(R.id.etNumberOfPhotos) }

    private val pvPreview by lazy { requireView().findViewById<PreviewView>(R.id.pvPreview) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initCameraXObservers()
        initSeekbarsListeners()
        initSwitchListeners()
        initOnClickListeners()
        initDoOnTextChangedListeners()
    }
    private fun initSeekbarsListeners(){
        sbWb.onSeekChangeListener = object : OnSeekChangeListener{
            override fun onSeeking(seekParams: SeekParams?) {
                singleViewModel.cameraX.wb.postValue(seekParams?.progress)
            }

            override fun onStartTrackingTouch(seekBar: IndicatorSeekBar?) {}
            override fun onStopTrackingTouch(seekBar: IndicatorSeekBar?) {}
        }
        sbFocus.onSeekChangeListener = object : OnSeekChangeListener{
            override fun onSeeking(seekParams: SeekParams?) {
                singleViewModel.cameraX.focus.postValue(seekParams?.progress)
            }

            override fun onStartTrackingTouch(seekBar: IndicatorSeekBar?) {}
            override fun onStopTrackingTouch(seekBar: IndicatorSeekBar?) {}
        }
        sbISO.onSeekChangeListener = object : OnSeekChangeListener{
            override fun onSeeking(seekParams: SeekParams?) {
                singleViewModel.cameraX.iso.postValue(seekParams?.progress?.plus(50)) // Min ISO always 50
            }

            override fun onStartTrackingTouch(seekBar: IndicatorSeekBar?) {}
            override fun onStopTrackingTouch(seekBar: IndicatorSeekBar?) {}
        }
        sbShutter.onSeekChangeListener = object : OnSeekChangeListener{
            override fun onSeeking(seekParams: SeekParams?) {
                singleViewModel.cameraX.shutter.postValue(seekParams?.progress)
            }

            override fun onStartTrackingTouch(seekBar: IndicatorSeekBar?) {}
            override fun onStopTrackingTouch(seekBar: IndicatorSeekBar?) {}
        }
        sbFrameDuration.onSeekChangeListener = object : OnSeekChangeListener{
            override fun onSeeking(seekParams: SeekParams?) {
                //cameraX.frameDuration.postValue(p1)
            }

            override fun onStartTrackingTouch(seekBar: IndicatorSeekBar?) {}
            override fun onStopTrackingTouch(seekBar: IndicatorSeekBar?) {}
        }
    }

    private fun initSwitchListeners() {
        switchAf.setOnCheckedChangeListener { _, isChecked ->
            if(isChecked) singleViewModel.cameraX.autoFocus.postValue(true)
            else singleViewModel.cameraX.autoFocus.postValue(false)
        }
        switchAutoIsoShutter.setOnCheckedChangeListener { _, isChecked ->
            if(isChecked) singleViewModel.cameraX.autoExposition.postValue(true)
            else singleViewModel.cameraX.autoExposition.postValue(false)
        }
        switchAutoWB.setOnCheckedChangeListener { _, isChecked ->
            if(isChecked) singleViewModel.cameraX.autoWB.postValue(true)
            else singleViewModel.cameraX.autoWB.postValue(false)
        }
        switchFlash.setOnCheckedChangeListener { _, isChecked ->
            if(isChecked) singleViewModel.cameraX.flash.postValue(true)
            else singleViewModel.cameraX.flash.postValue(false)
        }
    }

    private fun initOnClickListeners() {
        fabTakePicture.setOnClickListener {
            if (!etDelayBetweenPhoto.text.isNullOrEmpty() && !etNumberOfPhotos.text.isNullOrEmpty())
                singleViewModel.cameraX.initPhotoTimer(requireContext(),etDelayBetweenPhoto.text.toString().toLong(), etNumberOfPhotos.text.toString().toLong())
            else singleViewModel.cameraX.takePhoto(requireContext())
        }
    }

    private fun initDoOnTextChangedListeners() {
        etDelayBetweenPhoto.doOnTextChanged { text, start, before, count ->
            if(!text.isNullOrEmpty()) singleViewModel.cameraX.intervalBetweenShot = text.toString().toInt()
        }
        etNumberOfPhotos.doOnTextChanged { text, start, before, count ->
            if(!text.isNullOrEmpty()) singleViewModel.cameraX.numPhotos = text.toString().toInt()
        }
    }


    private fun initCameraXObservers(){
        val observerForCameraChange = Observer<Any> { _ ->
            pvPreview.doOnLayout { singleViewModel.cameraX.initCamera(viewLifecycleOwner, it as PreviewView) }
        }

        singleViewModel.cameraX.run {
            logAndSetupAvailableCameraSettings(requireContext())

            wb.observe(viewLifecycleOwner, observerForCameraChange)
            focus.observe(viewLifecycleOwner, observerForCameraChange)
            iso.observe(viewLifecycleOwner, observerForCameraChange)
            shutter.observe(viewLifecycleOwner, observerForCameraChange)
            frameDuration.observe(viewLifecycleOwner, observerForCameraChange)
            autoExposition.observe(viewLifecycleOwner, observerForCameraChange)
            autoFocus.observe(viewLifecycleOwner, observerForCameraChange)
            autoWB.observe(viewLifecycleOwner, observerForCameraChange)
            flash.observe(viewLifecycleOwner, observerForCameraChange)

            maxFocus.observe(viewLifecycleOwner, Observer { sbFocus.max = it.toFloat() })
            maxIso.observe(viewLifecycleOwner, Observer { sbISO.max = it.toFloat() })
            maxShutter.observe(viewLifecycleOwner, Observer { sbShutter.max = it.toFloat()})
        }
    }

    private fun showToastWith(it: String?) {
        Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
    }
}