package com.xiaohypercleaner.data

import java.io.IOException

class AdbException(message: String, cause: Throwable? = null) : IOException(message, cause)