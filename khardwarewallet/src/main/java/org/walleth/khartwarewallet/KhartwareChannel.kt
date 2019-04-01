package org.walleth.khartwarewallet

import com.payneteasy.tlv.BerTag
import com.payneteasy.tlv.BerTlvParser
import im.status.keycard.applet.ApplicationInfo
import im.status.keycard.applet.ApplicationStatus.TLV_APPLICATION_STATUS_TEMPLATE
import im.status.keycard.applet.KeycardCommandSet
import im.status.keycard.applet.KeycardCommandSet.GET_STATUS_P1_APPLICATION
import im.status.keycard.applet.TinyBERTLV
import im.status.keycard.io.CardChannel
import org.kethereum.bip39.model.MnemonicWords
import org.kethereum.crypto.SecureRandomUtils.secureRandom
import org.kethereum.crypto.api.ec.ECDSASignature
import org.kethereum.crypto.determineRecId
import org.kethereum.extensions.toBigInteger
import org.kethereum.functions.encodeRLP
import org.kethereum.keccakshortcut.keccak
import org.kethereum.model.PublicKey
import org.kethereum.model.SignatureData
import org.kethereum.model.SignedTransaction
import org.kethereum.model.Transaction
import org.walleth.khex.toHexString
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec


class KhartwareChannel(cardChannel: CardChannel) {

    private var cmdSet = KeycardCommandSet(cardChannel)
    private val blvParser by lazy { BerTlvParser() }

    val cardInfo: ApplicationInfo by lazy {

        val data = cmdSet.select().checkOK().data

        ApplicationInfo(data)
    }

    private fun ByteArray.toPublicKey(): PublicKey {

        if (first() != 4.toByte()) { // compression signaling
            throw java.lang.IllegalStateException("public key must start with 0x04 but was " + first() + " (full " + toHexString() + " size:$size )")
        }

        return PublicKey(copyOfRange(1, size))
    }

    fun autoPair(password: String) = cmdSet.autoPair(password)

    fun autoOpenSecureChannel() = cmdSet.autoOpenSecureChannel()

    fun generateMnemonic(checksumLength: Int, wordList: List<String>) =
        cmdSet.generateMnemonic(checksumLength).checkOK().data.let { responseList ->

            if (wordList.size != 2048) {
                throw java.lang.IllegalArgumentException("Wordlist must have a size of 2048 - but was" + wordList.size)
            }

            if (responseList.size != 24) {
                throw java.lang.IllegalStateException("Expected the result data to be 24 bytes but was ${responseList.size}")
            }

            val indexList = (0..11).map {
                responseList[it * 2].toPositiveInt().shl(8) or responseList[it * 2 + 1].toPositiveInt()
            }

            MnemonicWords(indexList.map { wordList[it] })
        }

    fun getStatus(): KhartwareStatus {
        val bytes = cmdSet.getStatus(GET_STATUS_P1_APPLICATION).checkOK().data

        val tinyBERTLV = TinyBERTLV(bytes)
        tinyBERTLV.enterConstructed(TLV_APPLICATION_STATUS_TEMPLATE.toInt())

        return KhartwareStatus(
            tinyBERTLV.readInt(),
            tinyBERTLV.readInt(),
            tinyBERTLV.readBoolean()
        )
    }

    fun initWithNewKey() {
        cmdSet.loadKey(createSecp256k1KeyPair()).checkOK()
    }

    fun removeKey() {
        cmdSet.removeKey()
    }

    fun verifyPIN(pin: String) {
        cmdSet.verifyPIN(pin).checkOK()
    }

    fun unpairOthers() = cmdSet.unpairOthers()
    fun autoUnpair() = cmdSet.autoUnpair()

    private var publicKey: PublicKey? = null

    fun toPublicKey() = cmdSet.exportCurrentKey(true).checkOK().data.let {
        val parsed = blvParser.parse(it)
        publicKey = parsed.list.first().values.first().bytesValue.toPublicKey()
        publicKey!!
    }

    fun sign(tx: Transaction): SignedTransaction {
        val chainId = tx.chain!!
        val encodeRLPHash = tx.encodeRLP(SignatureData().apply { v = chainId.toByte() }).keccak()

        val signedTransaction = cmdSet.sign(encodeRLPHash).checkOK().data

        val parsed = blvParser.parse(signedTransaction)

        val rootList = parsed.list
        if (rootList.size != 1 || rootList.first().tag != BerTag(0xa0)) {
            throw java.lang.IllegalArgumentException("Unexpected Signing result " + rootList)
        }

        val innerList = rootList.first().values

        if (innerList.size != 2 || innerList.last().tag != BerTag(0x30)) {
            throw java.lang.IllegalArgumentException("Unexpected Signing result (level 2) " + innerList.size + " " + innerList.last().tag)
        }

        val leafList = innerList.last().values


        if (leafList.size != 2 || leafList.first().tag != BerTag(0x02) || leafList.last().tag != BerTag(0x02)) {
            throw java.lang.IllegalArgumentException("Unexpected Signing result (leaf) $leafList")
        }

        val recId = ECDSASignature(
            leafList.first().bytesValue.toBigInteger(),
            leafList.last().bytesValue.toBigInteger()
        ).determineRecId(encodeRLPHash, publicKey!!)

        val signatureData = SignatureData(
            r = BigInteger(leafList.first().bytesValue),
            s = BigInteger(leafList.last().bytesValue),
            v = (recId + chainId * 2 + 8 + 27).toByte()
        )

        return SignedTransaction(tx, signatureData)
    }

}

// TODO replace with native uint when migrating to Kotlin 1.3
fun Byte.toPositiveInt() = toInt() and 0xFF

internal fun createSecp256k1KeyPair(): KeyPair {

    val keyPairGenerator = KeyPairGenerator.getInstance("ECDSA")
    val ecGenParameterSpec = ECGenParameterSpec("secp256k1")
    keyPairGenerator.initialize(ecGenParameterSpec, secureRandom())
    return keyPairGenerator.generateKeyPair()
}