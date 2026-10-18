package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface

/**
 * Finds the NCM control/data interface pair inside an active iPhone configuration.
 *
 * LIVI claims the control interface, claims the data interface, and selects data alternate
 * setting 1, the setting that carries the bulk endpoints. This class only reads descriptors.
 */
object NcmFunctionDiscovery {
    const val CONTROL_CLASS = 0x02
    const val CONTROL_SUBCLASS = 0x0d
    const val DATA_CLASS = 0x0a
    const val APPLE_ETHERNET_CLASS = 0xff
    const val APPLE_ETHERNET_SUBCLASS = 0xfd
    const val APPLE_ETHERNET_PROTOCOL = 0x01
    const val DATA_ALTERNATE_SETTING = 1

    data class NcmFunction(
        val control: UsbInterface,
        val data: UsbInterface,
        val statusIn: UsbEndpoint?,
        val bulkIn: UsbEndpoint,
        val bulkOut: UsbEndpoint,
    )

    fun find(configuration: UsbConfiguration): NcmFunction? {
        return findCdcNcm(configuration)
    }

    private fun findCdcNcm(configuration: UsbConfiguration): NcmFunction? {
        val all = interfaces(configuration)
        // Every NCM control interface (02/0d), in descriptor order ...
        val controls = all.filter {
            it.interfaceClass == CONTROL_CLASS && it.interfaceSubclass == CONTROL_SUBCLASS
        }.sortedBy { it.id }
        // ... and every data interface whose active setting carries bulk endpoints.
        val dataList = all.filter {
            it.interfaceClass == DATA_CLASS && bulkEndpoints(it) != null
        }.sortedWith(compareBy({ it.id }, { it.alternateSetting }))
        if (controls.isEmpty() || dataList.isEmpty()) return null

        // Pair each control with the data function that follows it (CDC NCM places a control and
        // its data consecutively). The pair whose CONTROL exposes an interrupt-IN status endpoint
        // is the NCM tethering function CarPlay uses; the pair without one is Apple's "private"
        // NCM used by RemoteXPC, not CarPlay. This is an explicit pair binding, not a guess.
        class Candidate(val control: UsbInterface, val data: UsbInterface, val status: UsbEndpoint?)
        val candidates = controls.mapIndexedNotNull { index, control ->
            val data = dataList.getOrNull(index) ?: return@mapIndexedNotNull null
            Candidate(control, data, statusInterrupt(control))
        }
        val chosen = candidates.firstOrNull { it.status != null } ?: candidates.firstOrNull() ?: return null
        val endpoints = bulkEndpoints(chosen.data) ?: return null
        android.util.Log.i(
            "DiPlayUsb",
            "ncm pair chosen control=if" + chosen.control.id + " data=if" + chosen.data.id +
                " statusInterrupt=" + (chosen.status != null) + " candidates=" +
                candidates.joinToString {
                    "if" + it.control.id + "/if" + it.data.id + "(status=" + (it.status != null) + ")"
                },
        )
        return NcmFunction(
            chosen.control,
            chosen.data,
            chosen.status,
            endpoints.first,
            endpoints.second,
        )
    }

    private fun statusInterrupt(control: UsbInterface): UsbEndpoint? =
        (0 until control.endpointCount).map(control::getEndpoint).singleOrNull {
            it.direction == UsbConstants.USB_DIR_IN && it.type == UsbConstants.USB_ENDPOINT_XFER_INT
        }

    private fun interfaces(configuration: UsbConfiguration): List<UsbInterface> =
        (0 until configuration.interfaceCount).map(configuration::getInterface)

    private fun bulkEndpoints(usbInterface: UsbInterface): Pair<UsbEndpoint, UsbEndpoint>? {
        val endpoints = (0 until usbInterface.endpointCount).map(usbInterface::getEndpoint)
        val input = endpoints.singleOrNull {
            it.direction == UsbConstants.USB_DIR_IN && it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
        }
        val output = endpoints.singleOrNull {
            it.direction == UsbConstants.USB_DIR_OUT && it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
        }
        return if (input != null && output != null) input to output else null
    }
}
