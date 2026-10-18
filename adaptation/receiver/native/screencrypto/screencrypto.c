/* Optional RFC8439 screen-only accelerator. No USB/file/network resources or persistent keys. */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include "vendor/monocypher-4.0.2/monocypher.h"

#define MAX_SEALED (8 * 1024 * 1024 + 16)
#define MAX_AAD (1024 * 1024)

JNIEXPORT jint JNICALL
Java_local_airuize_receiver_ScreenCryptoNative_nativeApiInfo(JNIEnv *env, jobject self) {
    (void)env; (void)self;
    return 0x0104;
}

JNIEXPORT jint JNICALL
Java_local_airuize_receiver_ScreenCryptoNative_nativeOpen(JNIEnv *env, jobject self,
        jbyteArray key_array, jbyteArray nonce_array, jbyteArray aad_array,
        jbyteArray sealed_array, jbyteArray output_array) {
    (void)self;
    if (!key_array || !nonce_array || !aad_array || !sealed_array || !output_array) return -2;
    if ((*env)->IsSameObject(env, output_array, key_array) ||
        (*env)->IsSameObject(env, output_array, nonce_array) ||
        (*env)->IsSameObject(env, output_array, aad_array) ||
        (*env)->IsSameObject(env, output_array, sealed_array)) return -2;
    const jsize size = (*env)->GetArrayLength(env, sealed_array);
    const jsize aad_size = (*env)->GetArrayLength(env, aad_array);
    if ((*env)->GetArrayLength(env, key_array) != 32 ||
        (*env)->GetArrayLength(env, nonce_array) != 12 ||
        size < 16 || size > MAX_SEALED || aad_size > MAX_AAD ||
        (*env)->GetArrayLength(env, output_array) != size - 16) return -2;

    uint8_t key[32] = {0}, nonce[12] = {0};
    crypto_aead_ctx ctx;
    uint8_t *body = (uint8_t *)malloc((size_t)size);
    uint8_t *aad = aad_size ? (uint8_t *)malloc((size_t)aad_size) : NULL;
    jint result = -2;
    if (!body || (aad_size && !aad)) goto cleanup;
    (*env)->GetByteArrayRegion(env, key_array, 0, 32, (jbyte *)key);
    if ((*env)->ExceptionCheck(env)) goto cleanup;
    (*env)->GetByteArrayRegion(env, nonce_array, 0, 12, (jbyte *)nonce);
    if ((*env)->ExceptionCheck(env)) goto cleanup;
    (*env)->GetByteArrayRegion(env, sealed_array, 0, size, (jbyte *)body);
    if ((*env)->ExceptionCheck(env)) goto cleanup;
    if (aad_size) (*env)->GetByteArrayRegion(env, aad_array, 0, aad_size, (jbyte *)aad);
    if ((*env)->ExceptionCheck(env)) goto cleanup;

    /* Reinitialize for every independent AirPlay frame; never use the incremental ratchet. */
    crypto_aead_init_ietf(&ctx, key, nonce);
    const size_t plain_size = (size_t)size - 16;
    if (crypto_aead_read(&ctx, body, body + plain_size, aad, (size_t)aad_size,
                         body, plain_size) != 0) {
        result = -1; /* No Java output is touched on authentication rejection. */
        goto cleanup;
    }
    if (plain_size) (*env)->SetByteArrayRegion(env, output_array, 0, (jsize)plain_size, (jbyte *)body);
    if (!(*env)->ExceptionCheck(env)) result = (jint)plain_size;
cleanup:
    crypto_wipe(&ctx, sizeof(ctx));
    crypto_wipe(key, sizeof(key));
    crypto_wipe(nonce, sizeof(nonce));
    if (body) { crypto_wipe(body, (size_t)size); free(body); }
    if (aad) { crypto_wipe(aad, (size_t)aad_size); free(aad); }
    return result;
}
