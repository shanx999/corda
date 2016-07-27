package com.r3corda.node.services.persistence

import com.r3corda.core.contracts.SignedTransaction
import com.r3corda.core.crypto.Party
import com.r3corda.core.failure
import com.r3corda.core.messaging.MessagingService
import com.r3corda.core.serialization.serialize
import com.r3corda.core.success
import com.r3corda.core.utilities.loggerFor
import com.r3corda.node.services.api.AbstractNodeService
import com.r3corda.node.services.api.ServiceHubInternal
import com.r3corda.protocols.FetchAttachmentsProtocol
import com.r3corda.protocols.FetchDataProtocol
import com.r3corda.protocols.FetchTransactionsProtocol
import com.r3corda.protocols.PartyRequestMessage
import com.r3corda.protocols.ResolveTransactionsProtocol
import java.io.InputStream
import javax.annotation.concurrent.ThreadSafe

/**
 * This class sets up network message handlers for requests from peers for data keyed by hash. It is a piece of simple
 * glue that sits between the network layer and the database layer.
 *
 * Note that in our data model, to be able to name a thing by hash automatically gives the power to request it. There
 * are no access control lists. If you want to keep some data private, then you must be careful who you give its name
 * to, and trust that they will not pass the name onwards. If someone suspects some data might exist but does not have
 * its name, then the 256-bit search space they'd have to cover makes it physically impossible to enumerate, and as
 * such the hash of a piece of data can be seen as a type of password allowing access to it.
 *
 * Additionally, because nodes do not store invalid transactions, requesting such a transaction will always yield null.
 */
@ThreadSafe
// TODO:  I don't like that this needs ServiceHubInternal, but passing in a state machine breaks MockServices because
//        the state machine isn't set when this is constructed. [NodeSchedulerService] has the same problem, and both
//        should be fixed at the same time.
class DataVendingService(net: MessagingService, private val services: ServiceHubInternal) : AbstractNodeService(net, services.networkMapCache) {
    companion object {
        val logger = loggerFor<DataVendingService>()

        /** Topic for messages notifying a node of a new transaction */
        val NOTIFY_TX_PROTOCOL_TOPIC = "platform.wallet.notify_tx"
    }

    val storage = services.storageService

    data class NotifyTxRequestMessage(val tx: SignedTransaction, override val replyToParty: Party, override val sessionID: Long) : PartyRequestMessage
    data class NotifyTxResponseMessage(val accepted: Boolean)

    init {
        addMessageHandler(FetchTransactionsProtocol.TOPIC,
                { req: FetchDataProtocol.Request -> handleTXRequest(req) },
                { message, e -> logger.error("Failure processing data vending request.", e) }
        )
        addMessageHandler(FetchAttachmentsProtocol.TOPIC,
                { req: FetchDataProtocol.Request -> handleAttachmentRequest(req) },
                { message, e -> logger.error("Failure processing data vending request.", e) }
        )
        addMessageHandler(NOTIFY_TX_PROTOCOL_TOPIC,
                { req: NotifyTxRequestMessage -> handleTXNotification(req) },
                { message, e -> logger.error("Failure processing data vending request.", e) }
        )
    }

    private fun handleTXNotification(req: NotifyTxRequestMessage): Unit {
        // TODO: We should have a whitelist of contracts we're willing to accept at all, and reject if the transaction
        //       includes us in any outside that list. Potentially just if it includes any outside that list at all.

        // TODO: Do we want to be able to reject specific transactions on more complex rules, for example reject incoming
        //       cash without from unknown parties?

        services.startProtocol(NOTIFY_TX_PROTOCOL_TOPIC, ResolveTransactionsProtocol(req.tx, req.replyToParty))
                .success {
                    services.recordTransactions(req.tx)
                    val resp = NotifyTxResponseMessage(true)
                    val msg = net.createMessage(NOTIFY_TX_PROTOCOL_TOPIC + "." + req.sessionID, resp.serialize().bits)
                    net.send(msg, req.getReplyTo(services.networkMapCache))
                }.failure {
                    val resp = NotifyTxResponseMessage(false)
                    val msg = net.createMessage(NOTIFY_TX_PROTOCOL_TOPIC + "." + req.sessionID, resp.serialize().bits)
                    net.send(msg, req.getReplyTo(services.networkMapCache))
                }
    }

    private fun handleTXRequest(req: FetchDataProtocol.Request): List<SignedTransaction?> {
        require(req.hashes.isNotEmpty())
        return req.hashes.map {
            val tx = storage.validatedTransactions.getTransaction(it)
            if (tx == null)
                logger.info("Got request for unknown tx $it")
            tx
        }
    }

    private fun handleAttachmentRequest(req: FetchDataProtocol.Request): List<ByteArray?> {
        // TODO: Use Artemis message streaming support here, called "large messages". This avoids the need to buffer.
        require(req.hashes.isNotEmpty())
        return req.hashes.map {
            val jar: InputStream? = storage.attachments.openAttachment(it)?.open()
            if (jar == null) {
                logger.info("Got request for unknown attachment $it")
                null
            } else {
                jar.readBytes()
            }
        }
    }
}
