package app.aaps.core.interfaces.maintenance

/**
 * Surface for non-interactive AAPS log export, intended for the Automation plugin.
 *
 * Companion to [ImportExportPrefs.exportSharedPreferencesNonInteractive] which
 * provides the same capability for settings exports. Exists in core/interfaces
 * so the automation module can depend on it without pulling in the full
 * configuration plugin.
 */
interface MaintenanceLogExporter {

    /**
     * Bundle the most-recent N log files into a zip and upload to the
     * configured cloud storage provider. Non-interactive — no UI, no email
     * fallback.
     *
     * Preconditions: cloud storage must be configured AND the
     * "Send logs to cloud" option must be enabled in Maintenance / Export
     * options. If either is missing the call returns false and the caller
     * should surface an appropriate notification.
     *
     * @return true if the zip was produced and the cloud upload coroutine
     *         was kicked off; false on any precondition or zip failure.
     */
    fun sendLogsNonInteractive(): Boolean
}
