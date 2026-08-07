package com.xiaohypercleaner.data

import java.io.IOException

enum class AdbErrorCode {
    NOT_CONNECTED, SOCKET_CLOSED, CONNECTION_FAILED, BAD_STATUS,
    PAYLOAD_TOO_LARGE, COMMAND_TOO_LONG, READ_TIMEOUT, UNEXPECTED_EOF
}

class AdbException(
    val code: AdbErrorCode,
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)