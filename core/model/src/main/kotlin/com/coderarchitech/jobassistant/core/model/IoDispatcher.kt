package com.coderarchitech.jobassistant.core.model

import javax.inject.Qualifier

/**
 * Qualifies the coroutine dispatcher for blocking disk and network work. Inject it instead of
 * using `Dispatchers.IO` directly so tests can swap it. It lives here, in the module every other
 * module already depends on; `:core:data` provides the binding.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher
