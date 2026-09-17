package com.hmndev.ziparc.data.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class VolumeKindTest {

    @Test
    fun primaryIsInternalEvenIfRemovableFlagOdd() {
        assertEquals(
            VolumeKind.INTERNAL,
            classifyVolume(
                isPrimary = true,
                isEmulated = false,
                isRemovable = true,
                usbMassStorageAttached = false
            )
        )
        assertEquals(
            VolumeKind.INTERNAL,
            classifyVolume(
                isPrimary = true,
                isEmulated = false,
                isRemovable = true,
                usbMassStorageAttached = true
            )
        )
        assertEquals(
            VolumeKind.INTERNAL,
            classifyVolume(
                isPrimary = true,
                isEmulated = false,
                isRemovable = false,
                usbMassStorageAttached = true
            )
        )
    }

    @Test
    fun emulatedIsInternal() {
        assertEquals(
            VolumeKind.INTERNAL,
            classifyVolume(
                isPrimary = false,
                isEmulated = true,
                isRemovable = false,
                usbMassStorageAttached = false
            )
        )
        assertEquals(
            VolumeKind.INTERNAL,
            classifyVolume(
                isPrimary = false,
                isEmulated = true,
                isRemovable = true,
                usbMassStorageAttached = false
            )
        )
        assertEquals(
            VolumeKind.INTERNAL,
            classifyVolume(
                isPrimary = false,
                isEmulated = true,
                isRemovable = true,
                usbMassStorageAttached = true
            )
        )
    }

    @Test
    fun removableWithUsbIsUsb() {
        assertEquals(
            VolumeKind.USB,
            classifyVolume(
                isPrimary = false,
                isEmulated = false,
                isRemovable = true,
                usbMassStorageAttached = true
            )
        )
    }

    @Test
    fun removableWithoutUsbIsSdCard() {
        assertEquals(
            VolumeKind.SD_CARD,
            classifyVolume(
                isPrimary = false,
                isEmulated = false,
                isRemovable = true,
                usbMassStorageAttached = false
            )
        )
    }

    @Test
    fun nonRemovableNonPrimaryIsInternal() {
        assertEquals(
            VolumeKind.INTERNAL,
            classifyVolume(
                isPrimary = false,
                isEmulated = false,
                isRemovable = false,
                usbMassStorageAttached = false
            )
        )
        assertEquals(
            VolumeKind.INTERNAL,
            classifyVolume(
                isPrimary = false,
                isEmulated = false,
                isRemovable = false,
                usbMassStorageAttached = true
            )
        )
    }

    @Test
    fun kindForVoldMajorTable() {
        assertEquals(VolumeKind.USB, kindForVoldMajor(8))
        assertEquals(VolumeKind.SD_CARD, kindForVoldMajor(179))
        assertEquals(null, kindForVoldMajor(259))
        assertEquals(null, kindForVoldMajor(7))
        assertEquals(null, kindForVoldMajor(0))
    }

    @Test
    fun parseMountKindsMapsUsbAndSdAndSkipsInternal() {
        val text = "/dev/block/vold/public:8,1 on /mnt/media_rw/68C9-D020 type vfat (rw,dirsync,nosuid,nodev,noexec,noatime,uid=1023,gid=1023,fmask=0007,dmask=0007,allow_utime=0020,codepage=437,iocharset=ascii,shortname=mixed,utf8,errors=remount-ro)\n" +
            "/dev/block/vold/public:179,65 on /mnt/media_rw/084E-056D type vfat (rw,dirsync,nosuid,nodev,noexec,noatime,uid=1023,gid=1023,fmask=0007,dmask=0007,allow_utime=0020,codepage=437,iocharset=ascii,shortname=mixed,utf8,errors=remount-ro)\n" +
            "/dev/block/dm-5 on /data type ext4 (rw,seclabel,nosuid,nodev,noatime)\n"
        val expected = mapOf(
            "68C9-D020" to VolumeKind.USB,
            "084E-056D" to VolumeKind.SD_CARD
        )
        assertEquals(expected, parseMountKinds(text))
    }
}
