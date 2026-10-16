package me.rerere.asr

import java.io.IOException
import java.net.ProtocolException
import javax.net.ssl.SSLException

/** Safe classification, not a provider response, URL, credential or stored Throwable. */
enum class ASRFailureKind { NETWORK, AUTHORIZATION, PROVIDER, PROTOCOL, UNKNOWN }

class ASRFailureException(val kind: ASRFailureKind) : IllegalStateException("ASR_${kind.name}")

fun classifyASRTransportFailure(error: Throwable, httpStatus: Int? = null): ASRFailureKind = when {
    httpStatus == 401 || httpStatus == 403 -> ASRFailureKind.AUTHORIZATION
    httpStatus != null && httpStatus != 101 -> ASRFailureKind.PROVIDER
    error is SSLException || error is ProtocolException -> ASRFailureKind.PROTOCOL
    error is IOException -> ASRFailureKind.NETWORK
    else -> ASRFailureKind.UNKNOWN
}

/** Old providers without a typed failure retain their existing compatibility path. */
fun ASRState.throwIfFailure() {
    if (errorMessage != null) {
        errorKind?.let { throw ASRFailureException(it) }
        throw IllegalStateException(errorMessage)
    }
}
