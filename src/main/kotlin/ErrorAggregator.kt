package de.mayope.errorcollector

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import de.mayope.errorcollector.issue.IssueService
import de.mayope.errorcollector.model.ExceptionContainer
import de.mayope.errorcollector.pastebin.PastebinClient
import de.mayope.errorcollector.pastebin.uploadText
import de.mayope.errorcollector.publish.ExceptionPublisher
import de.mayope.errorcollector.publish.PublishableException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

@Suppress("LongParameterList")
@ObsoleteCoroutinesApi
internal class ErrorAggregator(
    private val exceptionPublisher: ExceptionPublisher,
    private val serviceName: String,
    defaultDispatcher: CoroutineDispatcher? = null,
    private val issueService: IssueService? = null,
    private val pastebinClient: PastebinClient? = null,
    private val urlPastebin: String? = null,
    private val sendInterval: Duration,
) {

    private val dispatcher: CoroutineDispatcher = defaultDispatcher ?: Dispatchers.Default
    private val sendScope = CoroutineScope(SupervisorJob() + dispatcher)

    private val exceptionCollection = ConcurrentHashMap<String, ExceptionContainer>()

    init {
        sendScope.launch {
            while (isActive) {
                exceptionPublisher.publishExceptions(mapToPublish())
                exceptionCollection.clear()
                delay(sendInterval.toMillis())
            }
        }
    }

    private fun mapToPublish() = exceptionCollection.values.map {
        val pastebinLink = pastebinLink(it)
        PublishableException(it, issueLink(it.event.formattedMessage, stacktrace(it), pastebinLink), pastebinLink)
    }

    private fun issueLink(title: String, stacktrace: String, pastebinLink: String?) = issueService?.issueLink(
        title, stacktrace,
        serviceName, pastebinLink
    )

    private fun pastebinLink(exception: ExceptionContainer) = pastebinClient?.let {
        "$urlPastebin/${it.uploadText(stacktrace(exception))}"
    }

    private fun stacktrace(exception: ExceptionContainer) =
        ThrowableProxyUtil.asString(exception.event.throwableProxy) ?: ""

    fun registerMessage(eventObject: ILoggingEvent) {
        if (!exceptionCollection.containsKey(eventObject.formattedMessage)) {
            exceptionCollection[eventObject.formattedMessage] = ExceptionContainer(event = eventObject)
        }
        exceptionCollection[eventObject.formattedMessage]!!.count.getAndIncrement()
    }

    fun stop() {
        sendScope.cancel()
    }
}
