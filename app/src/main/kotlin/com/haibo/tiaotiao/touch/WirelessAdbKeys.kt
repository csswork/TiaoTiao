package com.haibo.tiaotiao.touch

import android.content.Context
import android.util.Base64
import com.haibo.tiaotiao.data.SecretVault
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

/** Local debugging identity is encrypted separately from every AI/sync credential. */
internal class WirelessAdbKeys(context: Context) {
    private val vault=SecretVault(context,"wireless_credentials","tiaotiao.wireless")
    val privateKey: PrivateKey
    val certificate: Certificate
    init {
        val existing=vault.get("identity").takeIf {it.isNotBlank()}?.split(':')
        if(existing?.size==2) {
            privateKey=KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.decode(existing[0],Base64.NO_WRAP)))
            certificate=CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(Base64.decode(existing[1],Base64.NO_WRAP)))
        } else {
            val pair=KeyPairGenerator.getInstance("RSA").apply {initialize(2048,SecureRandom())}.generateKeyPair()
            val provider=BouncyCastleProvider()
            val name=X500Name("CN=TiaoTiao Wireless")
            val builder=JcaX509v3CertificateBuilder(name,BigInteger(160,SecureRandom()),Date(System.currentTimeMillis()-86_400_000),
                Date(System.currentTimeMillis()+315_360_000_000),name,pair.public)
            val signed=builder.build(JcaContentSignerBuilder("SHA256withRSA").setProvider(provider).build(pair.private))
            privateKey=pair.private
            certificate=JcaX509CertificateConverter().setProvider(provider).getCertificate(signed)
            vault.set("identity",Base64.encodeToString(privateKey.encoded,Base64.NO_WRAP)+":"+Base64.encodeToString(certificate.encoded,Base64.NO_WRAP))
        }
    }
}
