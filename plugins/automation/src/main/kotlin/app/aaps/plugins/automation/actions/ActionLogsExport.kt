package app.aaps.plugins.automation.actions

import android.content.Context
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import app.aaps.core.data.model.TE
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.maintenance.MaintenanceLogExporter
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.notifications.NotificationUserMessage
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventNewNotification
import app.aaps.core.interfaces.rx.events.EventRefreshOverview
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.extensions.asAnnouncement
import app.aaps.core.objects.extensions.asSettingsExport
import app.aaps.core.utils.JsonHelper
import app.aaps.plugins.automation.R
import app.aaps.plugins.automation.elements.InputString
import app.aaps.plugins.automation.elements.LabelWithElement
import app.aaps.plugins.automation.elements.LayoutBuilder
import dagger.android.HasAndroidInjector
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import org.json.JSONObject
import javax.inject.Inject

/**
 * Automation action: export AAPS logs to cloud storage.
 *
 * Companion to [ActionSettingsExport] — same shape, same lifecycle, but
 * exports the log zip rather than the encrypted settings JSON. Requires:
 *   - cloud storage configured (Maintenance / Export options → Cloud Drive)
 *   - the "Send logs to cloud" toggle enabled
 *
 * The non-interactive log export bypasses the email Intent fallback in
 * MaintenancePlugin.sendLogs() — there is no Activity context when this runs
 * from an Automation trigger.
 *
 * Companion PR rationale: AAPS already supports auto settings export via
 * Automation (ActionSettingsExport) and the cloud-Drive upload code path
 * for logs is implemented in MaintenancePlugin.sendLogsToCloudDrive(). The
 * only missing piece was the Automation hook. This action closes that gap.
 */
class ActionLogsExport(injector: HasAndroidInjector) : Action(injector) {

    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var context: Context
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var config: Config
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var maintenanceLogExporter: MaintenanceLogExporter
    @Inject lateinit var preferences: Preferences

    private val disposable = CompositeDisposable()
    private val text = InputString()

    override fun friendlyName(): Int = R.string.exportlogs
    override fun shortDescription(): String = rh.gs(R.string.exportlogs_message, text.value)
    @DrawableRes override fun icon(): Int = app.aaps.core.objects.R.drawable.ic_export_settings_24dp

    override fun isValid(): Boolean = true

    override fun doAction(callback: Callback) {

        var exportResultMessage: String
        var exportResultComment: Int
        var notification: Notification
        var announceAlert = false

        aapsLogger.debug(LTag.AUTOMATION, "Exporting AAPS logs to cloud (non-interactive)")

        val ok = try {
            maintenanceLogExporter.sendLogsNonInteractive()
        } catch (t: Throwable) {
            aapsLogger.error(LTag.AUTOMATION, "Logs export threw: ${t.message}")
            false
        }

        if (ok) {
            exportResultComment = R.string.exportlogs_ok
            exportResultMessage = rh.gs(R.string.exportlogs_result_message_exported)
            notification = NotificationUserMessage(exportResultMessage, Notification.INFO)
        } else {
            exportResultComment = R.string.exportlogs_failed
            exportResultMessage = rh.gs(R.string.exportlogs_result_message_failed)
            notification = NotificationUserMessage(exportResultMessage, Notification.URGENT)
            announceAlert = true
        }

        rxBus.send(EventNewNotification(notification))

        // Audit-log to AAPS therapy events so this action is visible in NS + history.
        // Reuses EXPORT_SETTINGS action enum — see PR description for rationale.
        val error = "${text.value}: $exportResultMessage"
        aapsLogger.debug(LTag.AUTOMATION, "Insert therapy LOGS_EXPORT event, error=$error, doAlsoAnnouncement=$announceAlert")
        disposable += persistenceLayer.insertPumpTherapyEventIfNewByTimestamp(
            therapyEvent = TE.asSettingsExport(error = error),
            timestamp = dateUtil.now(),
            action = app.aaps.core.data.ue.Action.EXPORT_SETTINGS,
            source = Sources.Automation,
            note = exportResultMessage,
            listValues = listOf()
        ).subscribe()

        if (announceAlert && preferences.get(BooleanKey.NsClientCreateAnnouncementsFromErrors) && config.APS) {
            val alert = "${rh.gs(R.string.exportlogs_alert)}(${text.value}): $exportResultMessage"
            aapsLogger.debug(LTag.AUTOMATION, "Insert therapy ALERT/ANNOUNCEMENT event, error=$alert")
            disposable += persistenceLayer.insertPumpTherapyEventIfNewByTimestamp(
                therapyEvent = TE.asAnnouncement(error = alert),
                timestamp = dateUtil.now(),
                action = app.aaps.core.data.ue.Action.EXPORT_SETTINGS,
                source = Sources.Automation,
                note = exportResultMessage,
                listValues = listOf()
            ).subscribe()
        }

        rxBus.send(EventRefreshOverview("ActionLogsExport"))
        callback.result(pumpEnactResultProvider.get().success(ok).comment(exportResultComment)).run()
    }

    override fun toJSON(): String {
        val data = JSONObject().put("text", text.value)
        return JSONObject()
            .put("type", this.javaClass.simpleName)
            .put("data", data)
            .toString()
    }

    override fun fromJSON(data: String): Action {
        val o = JSONObject(data)
        text.value = JsonHelper.safeGetString(o, "text", "")
        return this
    }

    override fun hasDialog(): Boolean = true

    override fun generateDialog(root: LinearLayout) {
        LayoutBuilder()
            .add(LabelWithElement(rh, rh.gs(R.string.export_logs_short), "", text))
            .build(root)
    }
}
