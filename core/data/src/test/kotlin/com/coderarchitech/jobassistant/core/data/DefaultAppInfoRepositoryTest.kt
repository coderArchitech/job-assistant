package com.coderarchitech.jobassistant.core.data

import com.coderarchitech.jobassistant.core.model.BuildInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultAppInfoRepositoryTest {
    @Test
    fun returnsTheProvidedBuildInfo() {
        val info = BuildInfo(versionName = "1.2.3", flavor = "dev", configHost = "")
        assertEquals(info, DefaultAppInfoRepository(info).buildInfo())
    }

    @Test
    fun configHostIsSetOnlyWhenNotBlank() {
        assertFalse(BuildInfo("1.0", "prod", configHost = " ").isConfigHostSet)
        assertTrue(BuildInfo("1.0", "prod", configHost = "https://config.example.test/").isConfigHostSet)
    }
}
