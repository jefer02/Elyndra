package com.elyndra.launcher.update

import android.content.pm.PackageInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallResultTest {

    private fun result(status: Int, message: String? = null) = UpdateInstaller.resultOf(status, message)

    @Test fun `success and user cancel`() {
        assertEquals(InstallResult.Success, result(PackageInstaller.STATUS_SUCCESS))
        assertEquals(InstallResult.Cancelled, result(PackageInstaller.STATUS_FAILURE_ABORTED))
    }

    @Test fun `different signing key is recognised`() {
        assertEquals(InstallResult.SignatureMismatch, result(PackageInstaller.STATUS_FAILURE_CONFLICT, "INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package signatures do not match"))
        assertEquals(InstallResult.SignatureMismatch, result(PackageInstaller.STATUS_FAILURE_CONFLICT))
        assertEquals(InstallResult.SignatureMismatch, result(PackageInstaller.STATUS_FAILURE, "signatures do not match newer version"))
    }

    @Test fun `other failures keep the system message`() {
        val r = result(PackageInstaller.STATUS_FAILURE_INCOMPATIBLE, "INSTALL_FAILED_VERSION_DOWNGRADE")
        assertTrue(r is InstallResult.Failed)
        assertEquals("INSTALL_FAILED_VERSION_DOWNGRADE", (r as InstallResult.Failed).message)
        assertTrue(result(PackageInstaller.STATUS_FAILURE_STORAGE) is InstallResult.Failed)
    }
}
