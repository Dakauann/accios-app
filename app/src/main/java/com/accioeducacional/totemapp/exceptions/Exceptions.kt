package com.accioeducacional.totemapp.exceptions

open class SmartPresenceException(message: String, cause: Throwable? = null) : Exception(message, cause)

class NetworkException(message: String, cause: Throwable? = null) : SmartPresenceException(message, cause)
class PairingException(message: String, cause: Throwable? = null) : SmartPresenceException(message, cause)
class ConfigurationException(message: String, cause: Throwable? = null) : SmartPresenceException(message, cause)
class CryptographyException(message: String, cause: Throwable? = null) : SmartPresenceException(message, cause)
