package com.chatbyyourside.llm.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MnnJniContractTest {

    @Test
    fun runtimeInfoParserRecognizesUtf8StreamCapability() {
        val info = MnnRuntimeInfo.fromJson(
            """{"abiVersion":1,"mnnCommit":"commit","nativeBuildId":"build","capabilities":["summary_v2","utf8_stream_v1"]}""",
        )

        assertTrue(info != null)
        assertTrue(info!!.capabilities.contains(MnnBridge.CAPABILITY_UTF8_STREAM_V1))
        assertTrue(info.hasCapability(MnnBridge.CAPABILITY_UTF8_STREAM_V1))
    }

    @Test
    fun runtimeInfoWithoutUtf8CapabilityIsNotEligible() {
        val info = MnnRuntimeInfo.fromJson(
            """{"abiVersion":1,"mnnCommit":"commit","nativeBuildId":"build","capabilities":["summary_v2"]}""",
        )

        assertFalse(info!!.hasCapability(MnnBridge.CAPABILITY_UTF8_STREAM_V1))
    }

    @Test
    fun missingCapabilityThrowsOrdinaryUserFacingExceptionWithoutCallingNative() {
        var called = false

        val error = try {
            MnnJniContract.callUtf8(hasCapability = false) {
                called = true
                "should not run"
            }
            null
        } catch (e: JniContractMismatchException) {
            e
        }

        assertFalse(called)
        assertTrue(error is Exception)
        assertTrue(error!!.message!!.contains("UTF-8"))
    }

    @Test
    fun unsatisfiedLinkErrorIsWrappedAsOrdinaryContractException() {
        val error = try {
            MnnJniContract.callUtf8(hasCapability = true) {
                throw UnsatisfiedLinkError("missing nativeGenerateStreamUtf8")
            }
            null
        } catch (e: JniContractMismatchException) {
            e
        }

        assertTrue(error is Exception)
        assertEquals(UnsatisfiedLinkError::class.java, error!!.cause!!::class.java)
    }
}
