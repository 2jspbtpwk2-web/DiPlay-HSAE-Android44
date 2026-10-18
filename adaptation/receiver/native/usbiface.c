#include <jni.h>
#include <errno.h>
#include <linux/usbdevice_fs.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>

/*
 * The UsbDeviceConnection owns every fd passed here. This shim borrows the fd;
 * it must never close it, duplicate it, or change the active USB configuration.
 */
JNIEXPORT jint JNICALL
Java_local_airuize_receiver_UsbIfaceNative_nativeSetInterface(
        JNIEnv *env, jobject receiver, jint fd, jint interface_id, jint alternate) {
    (void) env;
    (void) receiver;
    if (fd < 0) return -EBADF;
    if (interface_id < 0 || interface_id > 255 || alternate < 0 || alternate > 255) {
        return -EINVAL;
    }
    struct usbdevfs_setinterface setting;
    memset(&setting, 0, sizeof(setting));
    setting.interface = (unsigned int) interface_id;
    setting.altsetting = (unsigned int) alternate;
    if (ioctl(fd, USBDEVFS_SETINTERFACE, &setting) < 0) return -errno;
    return 0;
}

JNIEXPORT jint JNICALL
Java_local_airuize_receiver_UsbIfaceNative_nativeBulkOut(
        JNIEnv *env, jobject receiver, jint fd, jint endpoint,
        jbyteArray bytes, jint timeout_millis) {
    (void) receiver;
    if (fd < 0) return -EBADF;
    /* Only nonzero bulk OUT endpoint addresses are accepted by this entry point. */
    if (endpoint < 1 || endpoint > 15 || bytes == NULL || timeout_millis <= 0) {
        return -EINVAL;
    }
    jsize length = (*env)->GetArrayLength(env, bytes);
    if (length <= 0 || length > 16384) return -EINVAL;
    void *copy = malloc((size_t) length);
    if (copy == NULL) return -ENOMEM;

    /*
     * Never hold a GetPrimitiveArrayCritical/GetByteArrayElements pin across
     * blocking USB I/O: Dalvik can otherwise prevent the other USB worker
     * threads from entering JNI while an IN request waits for phone traffic.
     */
    (*env)->GetByteArrayRegion(env, bytes, 0, length, (jbyte *) copy);
    if ((*env)->ExceptionCheck(env)) {
        free(copy);
        return -EFAULT;
    }
    struct usbdevfs_bulktransfer transfer;
    memset(&transfer, 0, sizeof(transfer));
    transfer.ep = (unsigned int) endpoint;
    transfer.len = (unsigned int) length;
    transfer.timeout = (unsigned int) timeout_millis;
    transfer.data = copy;
    int transferred = ioctl(fd, USBDEVFS_BULK, &transfer);
    int saved_errno = errno;
    free(copy);
    return transferred < 0 ? -saved_errno : transferred;
}

JNIEXPORT jint JNICALL
Java_local_airuize_receiver_UsbIfaceNative_nativeBulkIn(
        JNIEnv *env, jobject receiver, jint fd, jint endpoint,
        jbyteArray bytes, jint length, jint timeout_millis) {
    (void) receiver;
    if (fd < 0) return -EBADF;
    if (endpoint < 0x81 || endpoint > 0x8f || bytes == NULL ||
            length <= 0 || length > 16384 || timeout_millis <= 0) return -EINVAL;
    if (length > (*env)->GetArrayLength(env, bytes)) return -EINVAL;
    void *copy = malloc((size_t) length);
    if (copy == NULL) return -ENOMEM;
    struct usbdevfs_bulktransfer transfer;
    memset(&transfer, 0, sizeof(transfer));
    transfer.ep = (unsigned int) endpoint;
    transfer.len = (unsigned int) length;
    transfer.timeout = (unsigned int) timeout_millis;
    transfer.data = copy;
    /* Wait only on native memory; enter Java briefly after a completed read. */
    int transferred = ioctl(fd, USBDEVFS_BULK, &transfer);
    int saved_errno = errno;
    if (transferred > length) { free(copy); return -EOVERFLOW; }
    if (transferred > 0) {
        (*env)->SetByteArrayRegion(env, bytes, 0, transferred, (const jbyte *) copy);
        if ((*env)->ExceptionCheck(env)) { free(copy); return -EFAULT; }
    }
    free(copy);
    return transferred < 0 ? -saved_errno : transferred;
}

JNIEXPORT jint JNICALL
Java_local_airuize_receiver_UsbIfaceNative_nativeClearHalt(
        JNIEnv *env, jobject receiver, jint fd, jint endpoint) {
    (void) env;
    (void) receiver;
    if (fd < 0) return -EBADF;
    if (endpoint < 1 || endpoint > 15) return -EINVAL;
    unsigned int address = (unsigned int) endpoint;
    if (ioctl(fd, USBDEVFS_CLEAR_HALT, &address) < 0) return -errno;
    return 0;
}

/* API version in the high bytes, pointer width in the low byte (0x0304 on ARM/x86). */
JNIEXPORT jint JNICALL
Java_local_airuize_receiver_UsbIfaceNative_nativeAbiInfo(JNIEnv *env, jobject receiver) {
    (void) env;
    (void) receiver;
    return (3 << 8) | (jint) sizeof(void *);
}
